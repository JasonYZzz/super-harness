import { HttpAgent, type Message, type Interrupt } from '@ag-ui/client';
import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';

import { api, http } from '@/api/harness';
import { Button } from '@/components/ui/button';

interface Turn {
	runId: string;
	messageId?: string;
	input: string;
	response?: string;
	attempt: number;
	status: string;
	drafts: { attempt: number; text: string }[];
	toolEvents: { type: string; payload: { tasks?: Task[] } }[];
}
interface Task {
	subject?: string;
	title?: string;
	state?: string;
}
interface Decision {
	interruptId: string;
	status: 'resolved' | 'cancelled';
	payload?: { approved: boolean; argsHash: string };
}
interface Command {
	runId: string;
	id: string;
	text: string;
	resume?: Decision;
	resumeRunId?: string;
}

function history(turns: Turn[], before?: string) {
	const messages: Message[] = [];
	let tasks: Task[] = [];
	for (const turn of turns) {
		if (before && turn.messageId === before) break;
		messages.push({
			role: 'user',
			id: turn.messageId ?? turn.runId + '-input',
			content: turn.input,
		});
		const drafts = new Map(turn.drafts.map((d) => [d.attempt, d.text]));
		if (turn.response != null) drafts.set(turn.attempt, turn.response);
		for (const [attempt, text] of drafts)
			messages.push({
				role: 'assistant',
				id: turn.runId + '-' + attempt,
				content: text,
			});
		for (const event of turn.toolEvents)
			if (event.type === 'plan.updated') tasks = event.payload.tasks ?? [];
	}
	return { messages, tasks };
}

export default function AguiPage() {
	const { threadId = '' } = useParams();
	return <Interaction key={threadId} threadId={threadId} />;
}

function Interaction({ threadId }: { threadId: string }) {
	const key = 'harness.agui.' + threadId;
	const [text, setText] = useState('');
	const [messages, setMessages] = useState<Message[]>([]);
	const [tasks, setTasks] = useState<Task[]>([]);
	const [interrupt, setInterrupt] = useState<Interrupt>();
	const [status, setStatus] = useState('');
	const [busy, setBusy] = useState(true);
	const [retry, setRetry] = useState(false);
	const [platform, setPlatform] = useState('');
	const [files, setFiles] = useState<{ id: string; name: string }[]>([]);
	const [usage, setUsage] = useState<number>();
	const [tool, setTool] = useState('');
	const [revision, refresh] = useState(0);
	const current = useRef<Command | null>(null);
	const live = useRef<HttpAgent | null>(null);

	useEffect(() => {
		let mounted = true;
		async function load() {
			setBusy(true);
			setRetry(false);
			setPlatform('');
			setUsage(undefined);
			setTool('');
			setInterrupt(undefined);
			const turns = await api<Turn[]>('/conversations/' + threadId + '/messages');
			if (!mounted) return;
			const saved = sessionStorage.getItem(key);
			let cmd: Command | null = saved ? JSON.parse(saved) : null;
			const savedId = cmd?.id;
			const previous = turns.find((turn) => turn.messageId === savedId);
			if (
				cmd?.resume &&
				previous &&
				['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(previous.status)
			) {
				sessionStorage.removeItem(key);
				cmd = null;
			}
			current.current = cmd;
			const base = history(turns, cmd?.id);
			setMessages(base.messages);
			setTasks(base.tasks);
			if (!cmd) {
				const last = turns.at(-1);
				if (last) {
					setPlatform(last.runId);
					setStatus(last.status);
				}
				setBusy(false);
				return;
			}
			const agent = new HttpAgent({
				url: '/api/v1/ag-ui/runs',
				threadId,
				headers: {
					Authorization: 'Bearer ' + sessionStorage.getItem('harness.access'),
				},
				initialMessages: [...base.messages, { id: cmd.id, role: 'user', content: cmd.text }],
				initialState: { tasks: base.tasks },
			});
			live.current = agent;
			let terminal = false;
			let waiting: Interrupt | undefined;
			let code = '';
			let taskId = '';
			agent.subscribe({
				onMessagesChanged: ({ messages: next }) => {
					if (mounted) setMessages([...next]);
				},
				onStateChanged: ({ state }) => {
					if (mounted) setTasks(state.tasks ?? []);
				},
				onCustomEvent: ({ event }) => {
					if (!mounted) return;
					if (event.name === 'token_usage') setUsage(event.value.cumulative.totalTokens);
					if (event.name === 'harness.tool.started') setTool(event.value.name);
					if (event.name === 'harness.run.status') {
						taskId = event.value.runId;
						setPlatform(taskId);
						setStatus(event.value.status);
					}
				},
				onRunFinishedEvent: (event) => {
					terminal = true;
					waiting = event.outcome === 'interrupt' ? event.interrupts[0] : undefined;
				},
				onRunErrorEvent: ({ event }) => {
					terminal = true;
					code = event.code ?? event.message;
				},
			});
			setMessages([...agent.messages]);
			await agent.runAgent({ runId: cmd.runId });
			if (!mounted) return;
			if (
				waiting &&
				cmd.resume &&
				waiting.expiresAt &&
				Date.parse(waiting.expiresAt) <= Date.now()
			) {
				delete cmd.resume;
				delete cmd.resumeRunId;
				sessionStorage.setItem(key, JSON.stringify(cmd));
			}
			if (waiting && cmd.resume) {
				terminal = false;
				waiting = undefined;
				await agent.runAgent({ runId: cmd.resumeRunId, resume: [cmd.resume] });
				if (!mounted) return;
			}
			if (!terminal) throw new Error('连接已关闭，可重放本轮。');
			if (
				code &&
				cmd.resume &&
				!['CANCELLED', 'APPROVAL_REJECTED', 'DEADLINE_EXCEEDED'].includes(code)
			) {
				delete cmd.resume;
				delete cmd.resumeRunId;
				sessionStorage.setItem(key, JSON.stringify(cmd));
				throw new Error(code);
			}
			if (!waiting) {
				sessionStorage.removeItem(key);
				current.current = null;
			}
			setInterrupt(waiting);
			setStatus(code || (waiting ? '等待发布审批' : '已完成'));
			setBusy(false);
		}
		void load().catch((e) => {
			if (mounted) {
				setBusy(false);
				setRetry(!!current.current);
				setStatus(String(e.message ?? e));
			}
		});
		return () => {
			mounted = false;
			live.current?.abortRun();
			live.current = null;
		};
	}, [threadId, key, revision]);

	useEffect(() => {
		if (!interrupt || !platform || busy) return;
		let mounted = true;
		const timer = window.setInterval(async () => {
			try {
				const row = await api<{ status: string }>('/runs/' + platform);
				if (mounted && ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(row.status)) {
					sessionStorage.removeItem(key);
					setStatus(row.status);
					refresh((n) => n + 1);
				}
			} catch {
				/* The visible approval remains available for a manual retry. */
			}
		}, 2500);
		return () => {
			mounted = false;
			window.clearInterval(timer);
		};
	}, [busy, interrupt, key, platform]);

	useEffect(() => {
		let mounted = true;
		setFiles([]);
		if (platform && !busy && !interrupt)
			void api<{ id: string; name: string }[]>('/runs/' + platform + '/artifacts')
				.then((list) => {
					if (mounted) setFiles(list);
				})
				.catch((e) => {
					if (mounted) setStatus(e.message);
				});
		return () => {
			mounted = false;
		};
	}, [platform, busy, interrupt]);

	async function download(id: string, name: string) {
		try {
			const url = URL.createObjectURL(await (await http('/artifacts/' + id)).blob());
			const a = document.createElement('a');
			a.href = url;
			a.download = name;
			a.click();
			setTimeout(() => URL.revokeObjectURL(url), 1000);
		} catch (e) {
			setStatus(String(e));
		}
	}

	function execute(cmd: Command) {
		current.current = cmd;
		sessionStorage.setItem(key, JSON.stringify(cmd));
		setBusy(true);
		refresh((n) => n + 1);
	}
	function decide(approved: boolean | null) {
		if (!interrupt || !current.current) return;
		const decision: Decision =
			approved === null
				? { interruptId: interrupt.id, status: 'cancelled' }
				: {
						interruptId: interrupt.id,
						status: 'resolved',
						payload: {
							approved,
							argsHash: String(interrupt.metadata?.argsHash),
						},
					};
		execute({
			...current.current,
			resume: decision,
			resumeRunId: crypto.randomUUID(),
		});
	}
	return (
		<main className="mx-auto flex h-full w-full max-w-3xl flex-col gap-4 overflow-y-auto p-6">
			<h1 className="text-xl font-semibold">AG-UI 会话</h1>
			{messages
				.filter((m) => m.role === 'user' || m.role === 'assistant')
				.map((m) => (
					<section key={m.id}>
						<strong>
							{m.role === 'user'
								? '你'
								: Number(m.id.split('-').at(-1)) > 1
									? '助手 · 第 ' + m.id.split('-').at(-1) + ' 次尝试'
									: '助手'}
						</strong>
						<p className="whitespace-pre-wrap">{typeof m.content === 'string' ? m.content : ''}</p>
					</section>
				))}
			{tasks.length > 0 && (
				<section>
					<h2>计划</h2>
					<ul>
						{tasks.map((t, i) => (
							<li key={i}>
								{t.subject ?? t.title} · {t.state}
							</li>
						))}
					</ul>
				</section>
			)}
			<p role="status">
				{(
					{
						SUCCEEDED: '已完成',
						FAILED: '运行失败',
						CANCELLED: '已取消',
						WAITING_APPROVAL: '等待发布审批',
						accepted: '已接单',
						claimed: '已认领',
						started: '执行中',
						recovering: '恢复中',
						recovery_required: '等待恢复',
						cancel_requested: '正在取消',
					} as Record<string, string>
				)[status] ?? status}
			</p>
			{files.map((f) => (
				<Button key={f.id} variant="outline" onClick={() => void download(f.id, f.name)}>
					下载 {f.name}
				</Button>
			))}
			{usage != null && <p>本轮用量 {usage} tokens</p>}
			{tool && <p>工具 {tool}</p>}
			{busy && platform && (
				<Button
					variant="outline"
					onClick={() =>
						void api('/runs/' + platform + '/cancel', 'POST', {}).catch((e) => setStatus(e.message))
					}
				>
					取消任务
				</Button>
			)}
			{busy && platform && status === 'recovery_required' && (
				<Button
					onClick={() =>
						void api('/runs/' + platform + '/resume', 'POST', {}).catch((e) => setStatus(e.message))
					}
				>
					恢复执行
				</Button>
			)}
			{interrupt && (
				<section className="rounded-md border p-4">
					<h2>{interrupt.message}</h2>
					<p className="break-all text-xs">参数摘要 {String(interrupt.metadata?.argsHash)}</p>
					<div className="mt-3 flex gap-2">
						{interrupt.metadata?.canDecide && (
							<>
								<Button disabled={busy} onClick={() => decide(true)}>
									批准发布
								</Button>
								<Button variant="outline" disabled={busy} onClick={() => decide(false)}>
									拒绝发布
								</Button>
							</>
						)}
						<Button variant="outline" disabled={busy} onClick={() => decide(null)}>
							放弃本次发布
						</Button>
					</div>
				</section>
			)}
			{retry && (
				<Button variant="outline" onClick={() => refresh((n) => n + 1)}>
					重放本轮
				</Button>
			)}
			<form
				className="flex gap-2"
				onSubmit={(e) => {
					e.preventDefault();
					if (busy || interrupt || retry || !text.trim()) return;
					execute({
						id: crypto.randomUUID(),
						runId: crypto.randomUUID(),
						text,
					});
					setText('');
				}}
			>
				<input
					aria-label="消息"
					className="min-w-0 flex-1 rounded-md border px-3"
					value={text}
					maxLength={32000}
					onChange={(e) => setText(e.target.value)}
				/>
				<Button disabled={busy || !!interrupt || retry || !text.trim()}>发送</Button>
			</form>
		</main>
	);
}
