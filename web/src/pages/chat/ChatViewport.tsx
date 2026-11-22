import type { ToolCallBlock } from '@agentscope-ai/agentscope/message';
import {
	PermissionBehavior,
	createPermissionContext,
	type PermissionMode as SdkMode,
} from '@agentscope-ai/agentscope/permission';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
	Activity,
	BookText,
	Cable,
	ChevronDown,
	Database,
	History,
	ListTodo,
	PanelRight,
	ShieldCheck,
	SlidersHorizontal,
	UsersRound,
	X,
} from 'lucide-react';
import { useState, useRef, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { api, createSession, http, signedIn } from '@/api/harness';
import type { PermissionMode } from '@/api/types';
import { ChatContent } from '@/components/chat/ChatContent';
import { ConfirmCard } from '@/components/chat/ConfirmCard';
import { PanelDock, type PanelDescriptor, type PanelKey } from '@/components/panel/PanelDock';
import { PanelEmpty } from '@/components/panel/PanelEmpty';
import { PermissionPanel } from '@/components/panel/PermissionPanel';
import { TaskPanel } from '@/components/panel/TaskPanel';
import { TeamPanel } from '@/components/panel/TeamPanel';
import { LlmSelect } from '@/components/select/LlmSelect';
import { PermissionModeSelect } from '@/components/select/PermissionModeSelect';
import { RealtimeModelSelect } from '@/components/select/RealtimeModelSelect';
import { Button } from '@/components/ui/button';
import {
	Dialog,
	DialogContent,
	DialogHeader,
	DialogTitle,
	DialogDescription,
} from '@/components/ui/dialog';
import {
	DropdownMenu,
	DropdownMenuTrigger,
	DropdownMenuContent,
	DropdownMenuCheckboxItem,
	DropdownMenuItem,
} from '@/components/ui/dropdown-menu';
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from '@/components/ui/resizable';
import { Sheet, SheetClose, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { SidebarTrigger } from '@/components/ui/sidebar';
import { Tabs, TabsList, TabsContent, TabsTrigger } from '@/components/ui/tabs';
import { useHarnessChat, type Turn } from '@/hooks/useHarnessChat';
import { useSessions } from '@/hooks/useSessions';
import { useTranslation } from '@/i18n/useI18n';

type Approval = {
	id: string;
	runId: string;
	tool: string;
	preview: string;
	argsHash: string;
	canDecide: boolean;
	expiresAt: string;
};
function Runs({ turns, refresh }: { turns: Turn[]; refresh: () => void }) {
	const [expanded, setExpanded] = useState('');
	const trace = useQuery({
		queryKey: ['trace', expanded],
		enabled: !!expanded,
		queryFn: () => api('/runs/' + expanded + '/trace'),
	});
	const files = useQuery({
		queryKey: ['artifacts', expanded],
		enabled: !!expanded,
		queryFn: () => api<{ id: string; name: string }[]>('/runs/' + expanded + '/artifacts'),
	});
	return (
		<div className="flex flex-col gap-2 p-2">
			{turns.map((r) => (
				<Button
					key={r.runId}
					variant="ghost"
					className="justify-start"
					onClick={() => setExpanded(expanded === r.runId ? '' : r.runId)}
				>
					<span className="truncate">
						{r.input} · {r.status}
					</span>
				</Button>
			))}
			{turns
				.filter((t) => t.runId === expanded)
				.map((r) => (
					<div key={r.runId} className="rounded-xl border p-2 text-sm">
						<p>
							尝试 {r.attempt} · {r.status}
						</p>
						{r.status === 'RECOVERY_REQUIRED' && (
							<Button
								onClick={() =>
									api('/runs/' + r.runId + '/resume', 'POST', {})
										.then(refresh)
										.catch((e) => toast.error(String(e)))
								}
							>
								恢复
							</Button>
						)}
						{expanded === r.runId && (
							<>
								{files.data?.map((f) => (
									<Button
										key={f.id}
										onClick={async () => {
											try {
												const url = URL.createObjectURL(
													await (await http('/artifacts/' + f.id)).blob(),
												);
												const a = document.createElement('a');
												a.href = url;
												a.download = f.name;
												a.click();
												setTimeout(() => URL.revokeObjectURL(url), 1000);
											} catch (e) {
												toast.error(String(e));
											}
										}}
									>
										下载 {f.name}
									</Button>
								))}
								<pre className="overflow-auto text-xs">
									{JSON.stringify(trace.data, null, 2)}
								</pre>
							</>
						)}
					</div>
				))}
		</div>
	);
}
/** Maximum number of panels stacked in a single dock column. */
const MAX_PANELS_PER_COLUMN = 2;

/** localStorage key holding the dock layout across page navigations. */
const PANEL_LAYOUT_KEY = 'chat_panel_layout';

// Typed as a full Record so adding a PanelKey without listing it here
// is a compile error rather than a silently unrestorable panel.
const KNOWN_PANELS: Record<PanelKey, true> = {
	plan: true,
	mcp: true,
	skill: true,
	permission: true,
	knowledge: true,
	team: true,
	background: true,
	runs: true,
};

/**
 * Restore the persisted dock layout, dropping anything that is no
 * longer a known panel (keys get renamed/removed across releases).
 *
 * @returns The stored layout, or an empty one when absent or corrupt.
 */
function loadPanelLayout(): PanelKey[][] {
	try {
		const parsed: unknown = JSON.parse(localStorage.getItem(PANEL_LAYOUT_KEY) ?? '[]');
		if (!Array.isArray(parsed)) return [];
		return parsed
			.map((column: unknown) =>
				Array.isArray(column)
					? column.filter((key): key is PanelKey => key in KNOWN_PANELS)
					: [],
			)
			.filter((column) => column.length > 0);
	} catch {
		return [];
	}
}

/**
 * Insert a panel into the dock layout. Scans columns left to right and
 * appends to the first one with spare room; if every column is full a
 * new rightmost column is created. No-op when the panel is already
 * open.
 *
 * @param layout - The current column/panel arrangement.
 * @param key - The panel to open.
 * @returns A new layout array (the input is never mutated).
 */
function openPanelInLayout(layout: PanelKey[][], key: PanelKey): PanelKey[][] {
	if (layout.some((column) => column.includes(key))) return layout;
	const targetIndex = layout.findIndex((column) => column.length < MAX_PANELS_PER_COLUMN);
	if (targetIndex === -1) return [...layout, [key]];
	return layout.map((column, index) => (index === targetIndex ? [...column, key] : column));
}

/**
 * Remove a panel from the dock layout, dropping its column entirely if
 * it becomes empty.
 *
 * @param layout - The current column/panel arrangement.
 * @param key - The panel to close.
 * @returns A new layout array (the input is never mutated).
 */
function closePanelInLayout(layout: PanelKey[][], key: PanelKey): PanelKey[][] {
	return layout
		.map((column) => column.filter((panelKey) => panelKey !== key))
		.filter((column) => column.length > 0);
}
export function ChatViewport({
	agentId,
	sessionId,
	onSessionsChanged,
}: {
	agentId: string | null;
	sessionId: string | null;
	onSessionsChanged?: () => void;
}) {
	const chat = useHarnessChat(sessionId),
		nav = useNavigate(),
		cache = useQueryClient();
	const { sessions } = useSessions(agentId),
		session = sessions.find((v) => v.session.id === sessionId)?.session;
	const approvals = useQuery({
		queryKey: ['harness-approvals'],
		enabled: signedIn(),
		queryFn: () => api<Approval[]>('/approvals'),
		refetchInterval: 4000,
	});
	const { t } = useTranslation();
	const [modelHelp, setModelHelp] = useState(false);
	const [savingModel, setSavingModel] = useState(false);
	async function changeModel(model: string) {
		if (!agentId || savingModel) return;
		setSavingModel(true);
		try {
			if (sessionId) {
				await api('/conversations/' + sessionId + '/model', 'PUT', { model });
			} else {
				const created = await createSession(agentId, model);
				nav('/chat/' + agentId + '/' + created.session_id);
			}
			await cache.invalidateQueries({ queryKey: ['sessions', agentId] });
		} catch (error) {
			toast.error(String(error));
		} finally {
			setSavingModel(false);
		}
	}
	const [panelLayout, setPanelLayout] = useState<PanelKey[][]>(loadPanelLayout);
	const viewportRef = useRef<HTMLElement>(null);
	const [panelsInSheet, setPanelsInSheet] = useState(false);
	// Dismissing the narrow-screen overlay must preserve the desktop layout.
	const [sheetOpen, setSheetOpen] = useState(true);
	const [selectedSheetPanel, setSelectedSheetPanel] = useState<PanelKey | null>(null);

	useEffect(() => {
		const viewport = viewportRef.current;
		if (!viewport) return;
		let frame = 0;
		// Measure available chat space, including changes to the app sidebars.
		const observer = new ResizeObserver(([entry]) => {
			// Change layout in the next frame, outside the observer's delivery loop.
			window.cancelAnimationFrame(frame);
			frame = window.requestAnimationFrame(() => {
				setPanelsInSheet(entry.contentRect.width < 768);
			});
		});
		observer.observe(viewport);
		return () => {
			observer.disconnect();
			window.cancelAnimationFrame(frame);
		};
	}, []);

	const dockLayout = panelsInSheet ? [] : panelLayout;
	const sheetPanelKeys = panelLayout.flat();
	const activeSheetPanel =
		selectedSheetPanel && sheetPanelKeys.includes(selectedSheetPanel)
			? selectedSheetPanel
			: sheetPanelKeys[0];

	useEffect(() => {
		localStorage.setItem(PANEL_LAYOUT_KEY, JSON.stringify(panelLayout));
	}, [panelLayout]);
	const togglePanel = useCallback((key: PanelKey) => {
		setPanelLayout((layout) =>
			layout.some((column) => column.includes(key))
				? closePanelInLayout(layout, key)
				: openPanelInLayout(layout, key),
		);
	}, []);

	// Close a panel (driven by the panel's own close button).
	const closePanel = useCallback((key: PanelKey) => {
		setPanelLayout((layout) => closePanelInLayout(layout, key));
	}, []);

	const isPanelOpen = useCallback(
		(key: PanelKey) => panelLayout.some((column) => column.includes(key)),
		[panelLayout],
	);
	const openedPlan = useRef<string | null>(null);
	useEffect(() => {
		if (sessionId && chat.plan?.payload.tasks?.length && openedPlan.current !== sessionId) {
			openedPlan.current = sessionId;
			setPanelLayout((layout) => openPanelInLayout(layout, 'plan'));
		}
	}, [sessionId, chat.plan]);
	async function changePermission(mode: PermissionMode) {
		if (!sessionId || savingModel) return;
		setSavingModel(true);
		try {
			await api('/conversations/' + sessionId + '/permission', 'PUT', { mode });
			await cache.invalidateQueries({ queryKey: ['sessions', agentId] });
		} catch (e) {
			toast.error(String(e));
		} finally {
			setSavingModel(false);
		}
	}
	const labels: Record<PanelKey, string> = {
		plan: t('panel.plan.title'),
		background: t('panel.background.title'),
		permission: t('panel.permission.title'),
		team: t('common.team'),
		mcp: 'MCP',
		skill: t('panel.skill.title'),
		knowledge: t('panel.knowledge.title'),
		runs: t('harness.runs'),
	};

	const refresh = () => {
		void cache.invalidateQueries({ queryKey: ['harness-history', sessionId] });
		void approvals.refetch();
		onSessionsChanged?.();
	};
	const decide = async (a: Approval, approve: boolean) => {
		try {
			await api('/approvals/' + a.id + '/decision', 'POST', {
				approve,
				argsHash: a.argsHash,
			});
			refresh();
		} catch (e) {
			toast.error(String(e));
			throw e;
		}
	};
	const pending = approvals.data?.find((a) => chat.turns.some((r) => r.runId === a.runId));
	const approvalCard = pending?.canDecide ? (
		<ConfirmCard
			key={pending.id}
			toolCall={
				{
					id: pending.id,
					type: 'tool_call',
					name: pending.tool,
					state: 'asking',
					input: JSON.stringify({
						preview: pending.preview,
						argsHash: pending.argsHash,
						expiresAt: pending.expiresAt,
					}),
					created_at: chat.turns.find((r) => r.runId === pending.runId)!.createdAt,
				} as ToolCallBlock
			}
			onUserConfirm={(ok) => decide(pending, ok)}
		/>
	) : pending ? (
		<span>等待审批 · {pending.tool}</span>
	) : null;
	const permissionRules = createPermissionContext(session?.state.permission_mode as SdkMode);
	if (session?.state.publish_approval) {
		permissionRules.ask_rules.publish_text = [
			{
				tool_name: 'publish_text',
				behavior: PermissionBehavior.ASK,
				source: 'Java',
				rule_content: null,
			},
		];
	}
	const panels: Record<PanelKey, PanelDescriptor> = {
		runs: {
			title: labels.runs,
			icon: <History className="size-4" />,
			content: <Runs turns={chat.turns} refresh={refresh} />,
		},
		mcp: { title: 'MCP', icon: <Cable className="size-4" />, content: null },
		skill: { title: labels.skill, icon: <BookText className="size-4" />, content: null },
		knowledge: {
			title: labels.knowledge,
			icon: <Database className="size-4" />,
			content: null,
		},
		plan: {
			title: labels.plan,
			icon: <ListTodo className="size-4" />,
			content: (
				<TaskPanel
					tasksContext={
						chat.plan?.payload.tasks ? { tasks: chat.plan.payload.tasks } : null
					}
				/>
			),
		},
		background: {
			title: labels.background,
			icon: <Activity className="size-4" />,
			content: (
				<PanelEmpty
					icon={Activity}
					title={labels.background}
					description={t('harness.background')}
				/>
			),
		},
		team: {
			title: labels.team,
			icon: <UsersRound className="size-4" />,
			content: (
				<>
					<TeamPanel team={null} currentSessionId={sessionId} />
					<p className="p-3 text-xs text-muted-foreground">{t('harness.team')}</p>
				</>
			),
		},
		permission: {
			title: labels.permission,
			icon: <ShieldCheck className="size-4" />,
			content: (
				<div className="space-y-3 p-3 text-sm">
					<PermissionPanel permissionContext={permissionRules} />
					{approvals.data
						?.filter((a) => chat.turns.some((r) => r.runId === a.runId))
						.map((a) => (
							<div key={a.id} className="rounded-lg border p-2">
								<b>{a.tool}</b>
								<pre className="whitespace-pre-wrap">{a.preview}</pre>
								{a.canDecide && (
									<>
										<Button
											size="sm"
											onClick={() => void decide(a, true).catch(() => {})}
										>
											批准
										</Button>
										<Button
											variant="ghost"
											size="sm"
											onClick={() => void decide(a, false).catch(() => {})}
										>
											拒绝
										</Button>
									</>
								)}
							</div>
						))}
				</div>
			),
		},
	};
	const unavailable = async () => {
		throw new Error('Java 服务未启用此操作');
	};
	return (
		<main ref={viewportRef} className="flex size-full min-w-0">
			<ResizablePanelGroup orientation="horizontal">
				<ResizablePanel
					className="flex flex-1 rounded-[22px] bg-card shadow-panel"
					minSize={panelsInSheet ? '100%' : '24rem'}
				>
					<div className="flex flex-col flex-1 min-h-0 min-w-0 overflow-x-hidden p-2">
						<div className="flex flex-row gap-x-2 justify-between">
							<div
								className={
									panelsInSheet
										? 'flex min-w-0 max-w-24 items-center gap-x-1'
										: 'flex min-w-0 flex-row items-center gap-x-1'
								}
							>
								<SidebarTrigger className="md:hidden" />
								<span className="truncate px-2 text-sm text-muted-foreground">
									{chat.msgs.length > 0 ? session?.config.name : null}
								</span>
							</div>
							<div
								className={
									panelsInSheet
										? 'flex min-w-0 flex-1 flex-wrap justify-end gap-1'
										: 'flex shrink-0 flex-row gap-x-1'
								}
							>
								<RealtimeModelSelect
									disabled
									className="text-muted-foreground hover:text-foreground"
								/>
								<Button
									disabled
									variant="ghost"
									size="icon-sm"
									aria-label={t('realtime.parameters')}
								>
									<SlidersHorizontal />
								</Button>
								<LlmSelect
									id="tour-llm-select"
									variant="ghost"
									className="max-w-40 font-mono text-muted-foreground"
									value={session?.config.chat_model_config}
									onAddCredential={() => setModelHelp(true)}
									selectionDisabled={!agentId || !!chat.active || savingModel}
									onChange={(v) => {
										if (v) void changeModel(v.model);
									}}
								/>
								<Button
									variant="ghost"
									size="icon-sm"
									aria-label={t('llm-select.serverConfig')}
									onClick={() => setModelHelp(true)}
								>
									<SlidersHorizontal />
								</Button>
								<PermissionModeSelect
									id="tour-permission-mode"
									variant="ghost"
									className="font-mono text-muted-foreground hover:text-foreground"
									value={
										(session?.state.permission_mode as PermissionMode) ??
										'default'
									}
									disabled={!session || !!chat.active || savingModel}
									onChange={changePermission}
								/>
								<DropdownMenu>
									<DropdownMenuTrigger asChild>
										<Button
											variant="ghost"
											size="sm"
											aria-label="面板"
											className="gap-1 px-2"
										>
											<PanelRight />
											<ChevronDown className="size-3 text-muted-foreground" />
										</Button>
									</DropdownMenuTrigger>
									<DropdownMenuContent align="end" className="w-auto">
										{(
											[
												['background', t('panel.background.title')],
												['plan', t('panel.plan.title')],
												['mcp', 'MCP'],
												['skill', t('panel.skill.title')],
												['permission', t('panel.permission.title')],
												['knowledge', t('panel.knowledge.title')],
												['team', t('common.team')],
												['runs', labels.runs],
											] satisfies [PanelKey, string][]
										).map(([key, title]) =>
											panelsInSheet ? (
												<DropdownMenuItem
													key={key}
													disabled={!panels[key].content}
													onSelect={() => {
														setPanelLayout((layout) =>
															openPanelInLayout(layout, key),
														);
														setSelectedSheetPanel(key);
														setSheetOpen(true);
													}}
												>
													{panels[key].icon}
													{title}
												</DropdownMenuItem>
											) : (
												<DropdownMenuCheckboxItem
													key={key}
													disabled={!panels[key].content}
													checked={isPanelOpen(key)}
													onCheckedChange={() => togglePanel(key)}
													onSelect={(e) => e.preventDefault()}
												>
													{panels[key].icon}
													{title}
												</DropdownMenuCheckboxItem>
											),
										)}
									</DropdownMenuContent>
								</DropdownMenu>
							</div>
						</div>
						<div className="flex flex-1 justify-center min-h-0 overflow-hidden relative [--chat-content-w:48rem]">
							<ChatContent
								className="max-w-[var(--chat-content-w)] w-full"
								msgs={chat.msgs}
								loading={chat.loading}
								phase={chat.active ? 'streaming' : 'idle'}
								queuedCount={0}
								queuedItems={[]}
								queueReorderDisabled
								activeReplyId={chat.active?.runId ?? null}
								disabled={!signedIn() || !sessionId || savingModel}
								onSend={async (blocks) => {
									try {
										await chat.send(blocks);
										onSessionsChanged?.();
									} catch (e) {
										toast.error(String(e));
										throw e;
									}
								}}
								onInterrupt={() => {
									if (chat.active)
										void api(
											'/runs/' + chat.active.runId + '/cancel',
											'POST',
											{},
										)
											.then(refresh)
											.catch((e) => toast.error(String(e)));
								}}
								onUserConfirm={unavailable}
								onClientExternalToolResult={unavailable}
								onExternalExecutionResult={unavailable}
								onUpdateQueued={unavailable}
								onDeleteQueued={unavailable}
								onSteerQueued={unavailable}
								onMoveQueued={unavailable}
								onReorderQueued={unavailable}
								allowedInputTypes={['text/plain']}
								fileProcessor={async (file) => {
									if (file.type !== 'text/plain')
										throw new Error('Unsupported file type');
									return {
										id: crypto.randomUUID(),
										type: 'text',
										text: '[File: ' + file.name + ']\n' + (await file.text()),
										created_at: new Date().toISOString(),
									};
								}}
								agentId={agentId}
								sessionId={sessionId}
								cwd={null}
								onCwdChange={unavailable}
								asrDisabled
								voiceDisabled
								footerSlot={approvalCard}
							/>
						</div>
						{chat.error && <p role="alert">{chat.error.message}</p>}
					</div>
				</ResizablePanel>
				{dockLayout.length > 0 && (
					<ResizableHandle withHandle className="bg-transparent w-1.5" />
				)}
				<PanelDock layout={dockLayout} panels={panels} onClosePanel={closePanel} />
			</ResizablePanelGroup>
			<Dialog open={modelHelp} onOpenChange={setModelHelp}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>{t('llm-select.serverConfig')}</DialogTitle>
						<DialogDescription>{t('llm-select.serverInstructions')}</DialogDescription>
					</DialogHeader>
					<p className="text-sm">{t('llm-select.serverRestart')}</p>
				</DialogContent>
			</Dialog>
			{panelsInSheet && activeSheetPanel && (
				<Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
					<SheetContent
						className="gap-0 data-[side=right]:w-full sm:max-w-md"
						aria-describedby={undefined}
						showCloseButton={false}
					>
						<SheetHeader className="flex-row items-center justify-between gap-2">
							<SheetTitle className="min-w-0 text-sm">
								{panels[activeSheetPanel].title}
							</SheetTitle>
							<div className="flex shrink-0 items-center gap-1">
								{panels[activeSheetPanel].actions}
								<SheetClose asChild>
									<Button
										variant="ghost"
										size="icon-sm"
										aria-label={t('common.close')}
									>
										<X />
									</Button>
								</SheetClose>
							</div>
						</SheetHeader>
						<Tabs
							value={activeSheetPanel}
							onValueChange={(key) => setSelectedSheetPanel(key as PanelKey)}
							className="min-h-0 flex-1 gap-3 px-4 pb-4"
						>
							<div className="shrink-0 overflow-x-auto">
								<TabsList>
									{sheetPanelKeys.map((key) => (
										<TabsTrigger key={key} value={key}>
											{panels[key].icon}
											{panels[key].title}
										</TabsTrigger>
									))}
								</TabsList>
							</div>
							{sheetPanelKeys.map((key) => (
								<TabsContent
									key={key}
									value={key}
									className="min-h-0 overflow-y-auto"
								>
									{panels[key].content}
								</TabsContent>
							))}
						</Tabs>
					</SheetContent>
				</Sheet>
			)}
		</main>
	);
}
