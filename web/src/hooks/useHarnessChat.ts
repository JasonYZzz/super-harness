import {
	AssistantMsg,
	UserMsg,
	type Msg,
	type ContentBlock,
} from '@agentscope-ai/agentscope/message';
import type { TaskContext } from '@agentscope-ai/agentscope/state';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef } from 'react';

import { api, http } from '@/api/harness';

export interface Turn {
	runId: string;
	status: string;
	attempt: number;
	updatedAt: string;
	createdAt: string;
	input: string;
	response: string | null;
	lastSeq: number;
	toolEvents: Event[];
	drafts: { attempt: number; text: string }[];
}
interface Event {
	runId: string;
	attempt: number;
	seq: number;
	type: string;
	createdAt: string;
	payload: {
		text?: string;
		tool?: string;
		name?: string;
		decision?: string;
		tasks?: TaskContext['tasks'];
	};
}
const terminal = (status: string) => ['SUCCEEDED', 'CANCELLED', 'FAILED'].includes(status);
export function useHarnessChat(sessionId: string | null) {
	const cache = useQueryClient();
	const key = useMemo(() => ['harness-history', sessionId], [sessionId]);
	const history = useQuery<Turn[]>({
		queryKey: key,
		enabled: !!sessionId,
		queryFn: () => api('/conversations/' + sessionId + '/messages'),
		refetchInterval: 4000,
		structuralSharing: (oldData, newData) => {
			const old = oldData as Turn[] | undefined;
			return (newData as Turn[]).map((t) => {
				const prev = old?.find((r) => r.runId === t.runId);
				return prev && prev.lastSeq > t.lastSeq ? prev : t;
			});
		},
	});
	const turns = history.data ?? [];
	const active = turns.find((t) => !terminal(t.status));
	const activeId = active?.runId;
	useEffect(() => {
		if (!sessionId || !activeId) return;
		const ctrl = new AbortController();
		async function stream() {
			while (!ctrl.signal.aborted) {
				const row = cache.getQueryData<Turn[]>(key)?.find((t) => t.runId === activeId);
				if (!row || terminal(row.status)) return;
				try {
					const res = await http('/runs/' + activeId + '/events?afterSeq=' + row.lastSeq, {
						signal: ctrl.signal,
					});
					const reader = res.body!.getReader(),
						decoder = new TextDecoder();
					let buffer = '';
					try {
						while (!ctrl.signal.aborted) {
							const { value, done } = await reader.read();
							if (done) break;
							buffer += decoder.decode(value, { stream: true });
							let cut;
							while ((cut = buffer.indexOf('\n\n')) >= 0) {
								const line = buffer
									.slice(0, cut)
									.split('\n')
									.find((l) => l.startsWith('data:'));
								buffer = buffer.slice(cut + 2);
								if (!line) continue;
								const e: Event = JSON.parse(line.slice(5));
								if (e.runId !== activeId || !Number.isSafeInteger(e.seq))
									throw new Error('Invalid event');
								const snapshot = cache.getQueryData<Turn[]>(key) ?? [];
								const t = snapshot.find((t) => t.runId === activeId);
								if (!t || e.seq <= t.lastSeq) continue;
								if (e.seq !== t.lastSeq + 1) throw new Error('Missing event');
								const next = { ...t, lastSeq: e.seq, attempt: e.attempt };
								if (
									e.type.startsWith('tool.') ||
									e.type === 'approval.decided' ||
									e.type === 'plan.updated'
								)
									next.toolEvents = [...t.toolEvents, e];
								if (e.type === 'text.delta') {
									const drafts = t.drafts.filter((d) => d.attempt !== e.attempt);
									next.drafts = [
										...drafts,
										{
											attempt: e.attempt,
											text:
												(t.drafts.find((d) => d.attempt === e.attempt)?.text ?? '') +
												(e.payload.text ?? ''),
										},
									];
								}
								if (e.type === 'run.completed') {
									next.response = e.payload.text ?? '';
									next.status = 'SUCCEEDED';
								}
								cache.setQueryData(
									key,
									snapshot.map((r) => (r.runId === t.runId ? next : r)),
								);
								if (e.type.startsWith('run.') || e.type.startsWith('approval.')) {
									void cache.invalidateQueries({ queryKey: key });
									void cache.invalidateQueries({ queryKey: ['harness-approvals'] });
								}
							}
						}
					} finally {
						await reader.cancel().catch(() => {});
					}
				} catch {
					if (ctrl.signal.aborted) return;
				}
				if (!ctrl.signal.aborted) {
					await cache.invalidateQueries({ queryKey: key });
					await new Promise((resolve) => setTimeout(resolve, 400));
				}
			}
		}
		void stream();
		return () => ctrl.abort();
	}, [activeId, sessionId, key, cache]);
	const msgs: Msg[] = useMemo(
		() =>
			(history.data ?? []).flatMap((t) => {
				const stamp = new Date(t.createdAt).toISOString(),
					block = (id: string, text: string) => ({
						id,
						type: 'text' as const,
						text,
						created_at: stamp,
						finished_at: stamp,
					});
				const result: Msg[] = [
					UserMsg({
						id: t.runId + '-input',
						name: 'User',
						content: [block(t.runId + '-text', t.input)],
						created_at: stamp,
					}),
				];
				const attempts = new Map(t.drafts.map((d) => [d.attempt, d.text]));
				if (t.response != null) attempts.set(t.attempt, t.response);
				for (const e of t.toolEvents)
					if (e.type !== 'plan.updated' && !attempts.has(e.attempt)) attempts.set(e.attempt, '');
				for (const [attempt, text] of [...attempts].sort(([a], [b]) => a - b)) {
					const id = t.runId + '-' + attempt;
					const content: ContentBlock[] = text ? [block(id + '-text', text)] : [];
					let publication: string | undefined;
					for (const e of t.toolEvents.filter((e) => e.attempt === attempt)) {
						if (e.type === 'tool.requested' || e.type === 'tool.started') {
							const callId = id + '-tool-' + e.seq;
							content.push({
								id: callId,
								type: 'tool_call',
								name: e.payload.tool ?? e.payload.name ?? 'tool',
								input: JSON.stringify(e.payload),
								state: 'allowed',
								created_at: e.createdAt,
							});
							if (e.type === 'tool.requested') publication = callId;
						} else if (
							publication &&
							(e.type === 'tool.completed' || e.payload.decision === 'REJECTED')
						) {
							content.push({
								id: publication,
								type: 'tool_result',
								name: 'publish_text',
								output: JSON.stringify(e.payload),
								state: e.type === 'tool.completed' ? 'success' : 'denied',
								created_at: e.createdAt,
							});
							publication = undefined;
						}
					}
					const msg = AssistantMsg({ id, name: 'Agent', content, created_at: stamp });
					if (terminal(t.status) || attempt < t.attempt)
						msg.finished_at = new Date(t.updatedAt).toISOString();
					result.push(msg);
				}
				return result;
			}),
		[history.data],
	);
	const pending = useRef({ identity: '', key: '' });
	async function send(content: ContentBlock[]) {
		const text = content
			.filter((b) => b.type === 'text')
			.map((b) => b.text)
			.join('\n');
		if (!sessionId || !text.trim()) return;
		const identity = JSON.stringify([sessionId, text]);
		if (identity !== pending.current.identity)
			pending.current = { identity, key: crypto.randomUUID() };
		await api(
			'/runs',
			'POST',
			{ conversationId: sessionId, input: { type: 'text', text } },
			{ 'Idempotency-Key': pending.current.key },
		);
		pending.current = { identity: '', key: '' };
		await cache.invalidateQueries({ queryKey: key });
		void cache.invalidateQueries({ queryKey: ['sessions'] });
	}
	const plan = turns
		.filter((turn) => !['FAILED', 'CANCELLED', 'RECOVERY_REQUIRED'].includes(turn.status))
		.flatMap((turn) =>
			turn.toolEvents.filter((e) => e.attempt === turn.attempt && e.type === 'plan.updated'),
		)
		.at(-1);
	return {
		plan,
		msgs,
		turns,
		active,
		loading: !!sessionId && history.isPending,
		updatedAt: history.dataUpdatedAt,
		error: history.error,
		send,
	};
}
