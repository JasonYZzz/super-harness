import { HttpAgent, type Interrupt, type Message } from '@ag-ui/client';
import type { TaskContext } from '@agentscope-ai/agentscope/state';
import { useEffect, useRef, useState } from 'react';

export interface Turn {
	runId: string;
	messageId: string;
	interactionId?: string;
	input: string;
	status: string;
	attempt: number;
	createdAt: string;
	updatedAt: string;
	errorCode?: string;
}
export interface ToolActivity {
	id: string;
	messageId: string;
	name: string;
	mode: string;
}
interface Snapshot {
	cursor: string;
	turns: Turn[];
	messages: Message[];
	state: { tasks: TaskContext['tasks'] };
	tools: ToolActivity[];
	interrupts: Interrupt[];
	archived: boolean;
	capabilities: { humanInTheLoop: { interrupts: boolean }; inputContentTypes: string[] };
}
interface Decision {
	interruptId: string;
	status: 'resolved' | 'cancelled';
	payload?: { approved: boolean; argsHash: string };
}
type Command =
	| { kind: 'send'; runId: string; id: string; text: string }
	| { kind: 'resume'; runId: string; decision: Decision; toolCallId?: string };

function readCommand(key: string): Command | null {
	const saved = sessionStorage.getItem(key);
	if (!saved) return null;
	try {
		const cmd = JSON.parse(saved);
		if (typeof cmd?.runId === 'string' && cmd.runId) {
			if (
				cmd.kind === 'send' &&
				typeof cmd.id === 'string' &&
				cmd.id &&
				typeof cmd.text === 'string' &&
				cmd.text.trim()
			)
				return cmd;
			const decision = cmd.decision;
			if (
				cmd.kind === 'resume' &&
				typeof decision?.interruptId === 'string' &&
				decision.interruptId &&
				(decision.status === 'cancelled' ||
					(decision.status === 'resolved' &&
						typeof decision.payload?.approved === 'boolean' &&
						typeof decision.payload.argsHash === 'string'))
			)
				return cmd;
		}
	} catch {
		// An invalid local command cannot be replayed. Recover the authorized server snapshot.
	}
	sessionStorage.removeItem(key);
	return null;
}

function threadAgent(threadId: string, initialMessages: Message[] = [], initialState = {}) {
	return new HttpAgent({
		url: '/api/v1/ag-ui/runs',
		threadId,
		initialMessages,
		initialState,
		headers: { Authorization: 'Bearer ' + sessionStorage.getItem('harness.access') },
	});
}

async function readSnapshot(agent: HttpAgent, since: string): Promise<Snapshot | undefined> {
	let snapshot: Snapshot | undefined;
	let complete = false;
	await agent.runAgent(
		{ forwardedProps: { 'harness.snapshot': since ? { since } : {} } },
		{
			onRunFinishedEvent: (finished) => {
				const metadata = finished.event.result;
				if (metadata?.unchanged === true && since) {
					complete = true;
				} else if (typeof metadata?.cursor === 'string') {
					snapshot = {
						...metadata,
						messages: [...agent.messages],
						state: { tasks: agent.state.tasks },
						interrupts: finished.outcome === 'interrupt' ? finished.interrupts : [],
					};
					complete = true;
				}
			},
		},
	);
	if (!complete) throw new Error('会话快照不完整');
	return snapshot;
}

const empty: Snapshot = {
	cursor: '',
	turns: [],
	messages: [],
	tools: [],
	interrupts: [],
	state: { tasks: [] },
	archived: false,
	capabilities: { humanInTheLoop: { interrupts: false }, inputContentTypes: [] },
};
const statusNames: Record<string, string> = {
	accepted: 'QUEUED',
	claimed: 'CLAIMED',
	started: 'RUNNING',
	recovering: 'RECOVERING',
	recovery_required: 'RECOVERY_REQUIRED',
	cancel_requested: 'CANCEL_REQUESTED',
};
export const terminal = (s: string) => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(s);
function activeTurn(turns: Turn[]) {
	return (
		turns.find((t) => !terminal(t.status) && t.status !== 'QUEUED') ??
		turns.find((t) => t.status === 'QUEUED')
	);
}

/** Rebuild the input boundary before feeding an entire protocol run to the SDK reducer. */
function interaction(snapshot: Snapshot, command: Command | null) {
	if (!command) {
		const open = activeTurn(snapshot.turns);
		if (!open || snapshot.interrupts.length) return;
		return { messages: [], parameters: { forwardedProps: { 'harness.observe': open.runId } } };
	}
	if (command.kind === 'resume') {
		return {
			messages: snapshot.messages.filter(
				(m) => m.role !== 'tool' || m.toolCallId !== command.toolCallId,
			),
			parameters: { runId: command.runId, resume: [command.decision] },
		};
	}
	const boundary = snapshot.messages.findIndex((m) => m.role === 'user' && m.id === command.id);
	const messages: Message[] =
		boundary >= 0
			? snapshot.messages.slice(0, boundary + 1)
			: [...snapshot.messages, { role: 'user', id: command.id, content: command.text }];
	return { messages, parameters: { runId: command.runId } };
}

export function useAguiThread(threadId: string | null) {
	const [view, setView] = useState(empty);
	const [busy, setBusy] = useState(true);
	const [error, setError] = useState('');
	const [retry, setRetry] = useState(false);
	const [usage, setUsage] = useState<{ task: string; tokens: number }>();
	const [revision, refresh] = useState(0);
	const locked = useRef(true);
	const baseline = useRef<Snapshot | undefined>(undefined);
	const key = 'harness.chat.command.' + threadId;
	const active = activeTurn(view.turns);
	const interrupt = view.interrupts[0];

	useEffect(() => {
		if (!threadId) return;
		const thread = threadId;
		let mounted = true;
		let timer: number;
		let agent: HttpAgent | undefined;
		let cursor = '';
		let failures = 0;
		let command = readCommand(key);
		let initialSnapshot = command ? baseline.current : undefined;
		function lock(value: boolean) {
			locked.current = value;
			setBusy(value);
		}
		function clearCommand() {
			command = null;
			sessionStorage.removeItem(key);
		}
		function schedule(delay = 4000) {
			timer = window.setTimeout(() => void sync(), delay);
		}
		async function sync() {
			let snapshotLoaded = false;
			try {
				let snapshot = initialSnapshot;
				if (!snapshot) {
					agent = threadAgent(thread);
					snapshot = await readSnapshot(agent, cursor);
				}
				initialSnapshot = undefined;
				if (!mounted) return;
				if (!snapshot) {
					schedule();
					return;
				}
				snapshotLoaded = true;
				baseline.current = snapshot;
				setView(snapshot);
				cursor = snapshot.cursor;
				const request = interaction(snapshot, command);
				if (!request) {
					lock(false);
					setRetry(false);
					schedule();
					return;
				}
				const decision = command?.kind === 'resume' ? command.decision : undefined;
				if (
					decision &&
					snapshot.interrupts.some(
						(i) =>
							i.id === decision.interruptId && i.expiresAt && Date.parse(i.expiresAt) <= Date.now(),
					)
				) {
					clearCommand();
					setError('审批已过期');
					lock(false);
					schedule();
					return;
				}
				lock(true);
				setRetry(false);
				agent = threadAgent(thread, request.messages, snapshot.state);
				let streamTask: string | undefined;
				let ended = false;
				let code = '';
				if (command) setView((v) => ({ ...v, messages: request.messages }));
				agent.subscribe({
					onMessagesChanged: ({ messages }) => {
						if (mounted) setView((v) => ({ ...v, messages: [...messages] }));
					},
					onStateChanged: ({ state }) => {
						if (mounted) setView((v) => ({ ...v, state: { tasks: state.tasks ?? [] } }));
					},
					onCustomEvent: ({ event }) => {
						if (!mounted) return;
						if (event.name === 'token_usage' && streamTask)
							setUsage({ task: streamTask, tokens: event.value.cumulative.totalTokens });
						if (event.name === 'harness.tool.started') {
							const tool: ToolActivity = event.value;
							setView((v) => ({ ...v, tools: [...v.tools.filter((t) => t.id !== tool.id), tool] }));
						}
						if (event.name === 'harness.run.status') {
							const { runId: taskId, status, attempt } = event.value;
							streamTask = taskId;
							const now = new Date(event.timestamp ?? Date.now()).toISOString();
							const newTurn: Turn = {
								runId: taskId,
								messageId: command?.kind === 'send' ? command.id : '',
								input: command?.kind === 'send' ? command.text : '',
								createdAt: now,
								updatedAt: now,
								status: statusNames[status] ?? status,
								attempt,
							};
							setView((v) => ({
								...v,
								turns: v.turns.some((t) => t.runId === taskId)
									? v.turns.map((t) =>
											t.runId === taskId ? { ...t, status: newTurn.status, attempt } : t,
										)
									: [...v.turns, newTurn],
							}));
						}
					},
					onRunFinishedEvent: (event) => {
						ended = true;
						if (mounted)
							setView((v) => ({
								...v,
								interrupts: event.outcome === 'interrupt' ? event.interrupts : [],
							}));
					},
					onRunErrorEvent: ({ event }) => {
						ended = true;
						code = event.code ?? event.message;
					},
				});
				await agent.runAgent(request.parameters);
				if (!mounted) return;
				if (!ended) throw new Error('连接中断');
				clearCommand();
				setError(code);
				failures = 0;
				cursor = '';
				schedule(0);
			} catch (e) {
				if (!mounted) return;
				const { status, payload } = e as { status?: number; payload?: { code?: string } | string };
				const message =
					(typeof payload === 'object' && payload?.code) ||
					(e instanceof Error ? e.message : String(e));
				if (status === 401) {
					sessionStorage.removeItem('harness.access');
					window.location.replace('/setup');
					return;
				}
				if (status === 409 && message.includes('CONVERSATION_ARCHIVED')) {
					setView((v) => ({ ...v, archived: true }));
				}
				setError(message);
				if (status && status >= 400 && status < 500 && status !== 401 && status !== 408) {
					clearCommand();
					if (!snapshotLoaded) {
						baseline.current = undefined;
						setView(empty);
					}
					setRetry(false);
					lock(false);
					cursor = '';
					if (snapshotLoaded) schedule(0);
				} else if (failures++ === 0) {
					cursor = '';
					schedule(400);
				} else {
					lock(false);
					setRetry(true);
				}
			}
		}
		lock(true);
		setRetry(false);
		setError('');
		void sync();
		return () => {
			mounted = false;
			window.clearTimeout(timer);
			agent?.abortRun();
		};
	}, [threadId, key, revision]);

	function execute(cmd: Command) {
		if (locked.current || !threadId || retry) throw new Error('请等待当前操作完成');
		locked.current = true;
		setBusy(true);
		sessionStorage.setItem(key, JSON.stringify(cmd));
		refresh((n) => n + 1);
	}
	const canSend =
		!!threadId && !busy && !retry && !active && !interrupt && !view.archived && !!view.cursor;
	async function control(turn: Turn, action: 'cancel' | 'recover') {
		if (!threadId) throw new Error('请先选择会话');
		const forwardedProps =
			action === 'cancel'
				? { 'harness.cancel': turn.runId }
				: { 'harness.recover': { taskId: turn.runId, attempt: turn.attempt } };
		const { result } = await threadAgent(threadId).runAgent({ forwardedProps });
		if (result?.taskId !== turn.runId) throw new Error('未收到任务操作回执');
		refresh((n) => n + 1);
	}

	function send(text: string) {
		if (!canSend || !text.trim()) throw new Error('当前会话无法发送消息');
		setUsage(undefined);
		execute({ kind: 'send', id: crypto.randomUUID(), text, runId: crypto.randomUUID() });
	}
	function decide(approved: boolean | null) {
		if (!interrupt || !active) throw new Error('审批已结束');
		const resume: Decision =
			approved === null
				? { interruptId: interrupt.id, status: 'cancelled' }
				: {
						interruptId: interrupt.id,
						status: 'resolved',
						payload: { approved, argsHash: String(interrupt.metadata?.argsHash) },
					};
		execute({
			kind: 'resume',
			runId: crypto.randomUUID(),
			toolCallId: interrupt.toolCallId,
			decision: resume,
		});
	}
	return {
		...view,
		active,
		interrupt,
		busy,
		canSend,
		usage: usage && usage.task === (active ?? view.turns.at(-1))?.runId ? usage.tokens : undefined,
		error,
		retry,
		send,
		decide,
		control,
		refresh: () => refresh((n) => n + 1),
	};
}
