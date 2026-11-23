import {
	AssistantMsg,
	UserMsg,
	type Msg,
	type ContentBlock,
} from '@agentscope-ai/agentscope/message';
import { useMemo } from 'react';

import { terminal, useAguiThread } from './useAguiThread';

// AgentScope types are confined to the existing rich chat renderer.
export function useHarnessChat(threadId: string | null) {
	const chat = useAguiThread(threadId);
	const { messages, turns, tools } = chat;
	const msgs: Msg[] = useMemo(() => {
		const byRun = new Map(turns.map((t) => [t.runId, t]));
		const byUser = new Map(turns.map((t) => [t.messageId, t]));
		const results = new Map(
			messages.filter((m) => m.role === 'tool').map((m) => [m.toolCallId, m]),
		);
		return messages.flatMap((m): Msg[] => {
			if (m.role !== 'user' && m.role !== 'assistant') return [];
			const turn =
				m.role === 'user' ? byUser.get(m.id) : byRun.get(m.id.slice(0, m.id.lastIndexOf('-')));
			const stamp = turn?.createdAt ?? new Date().toISOString();
			const content: ContentBlock[] = [];
			if (typeof m.content === 'string' && m.content)
				content.push({ id: m.id + '-text', type: 'text', text: m.content, created_at: stamp });
			if (m.role === 'user')
				return [UserMsg({ id: m.id, name: 'User', content, created_at: stamp })];
			for (const t of tools.filter((t) => t.messageId === m.id))
				content.push({
					type: 'tool_call',
					id: t.id,
					name: t.name,
					input: JSON.stringify({ mode: t.mode }),
					state: 'allowed',
					created_at: stamp,
				});
			for (const call of m.toolCalls ?? []) {
				const result = results.get(call.id);
				content.push({
					type: 'tool_call',
					id: call.id,
					name: call.function.name,
					input: call.function.arguments,
					state: result ? 'finished' : 'allowed',
					created_at: stamp,
				});
				if (result)
					content.push({
						type: 'tool_result',
						id: call.id,
						name: call.function.name,
						output:
							typeof result.content === 'string' ? result.content : JSON.stringify(result.content),
						state:
							turn?.status === 'CANCELLED'
								? 'interrupted'
								: turn?.status === 'FAILED'
									? 'denied'
									: 'success',
						created_at: stamp,
					});
			}
			const reply = AssistantMsg({ id: m.id, name: 'Agent', content, created_at: stamp });
			if (turn && (terminal(turn.status) || m.id !== turn.runId + '-' + turn.attempt))
				reply.finished_at = turn.updatedAt;
			return [reply];
		});
	}, [messages, turns, tools]);
	return { ...chat, msgs };
}
