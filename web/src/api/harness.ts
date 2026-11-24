import { format } from 'date-fns';

import type { AgentView, PermissionMode, SessionView } from './types';

export const signedIn = () => Boolean(sessionStorage.getItem('harness.access'));
export async function http(path: string, init: RequestInit = {}) {
	const headers = new Headers(init.headers);
	if (!headers.has('Authorization'))
		headers.set('Authorization', 'Bearer ' + (sessionStorage.getItem('harness.access') ?? ''));
	if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json');
	const res = await fetch('/api/v1' + path, { ...init, headers });
	if (res.status === 401 && signedIn() && !new Headers(init.headers).has('Authorization')) {
		sessionStorage.removeItem('harness.access');
		window.location.replace('/setup');
	}
	if (!res.ok) {
		const body = await res.json().catch(() => ({}));
		throw Object.assign(new Error(body.code ?? 'HTTP ' + res.status), { status: res.status });
	}
	return res;
}
export async function api<T>(
	path: string,
	method = 'GET',
	body?: unknown,
	headers?: Record<string, string>,
): Promise<T> {
	const r = await http(path, {
		method,
		headers,
		body: body instanceof FormData ? body : JSON.stringify(body),
	});
	const s = await r.text();
	return (s ? JSON.parse(s) : undefined) as T;
}
export interface Revision {
	id: string;
	revision: number;
	model: string;
	prompt: string;
	config: string | Record<string, unknown>;
}
export const revisions = (id: string) => api<Revision[]>('/agents/' + id + '/revisions');
export interface AgentInput {
	name?: string;
	system_prompt?: string;
	model?: string;
	publish_artifact?: boolean;
	use_memory?: boolean;
	skills?: string[];
	sandbox?: boolean;
	mcp_profile?: string;
}
export async function saveAgent(body: AgentInput, id?: string) {
	const latest = id ? (await revisions(id))[0] : undefined;
	const config = typeof latest?.config === 'string' ? JSON.parse(latest.config) : latest?.config;
	return api<{ agentId: string }>('/agents' + (id ? '/' + id : ''), id ? 'PATCH' : 'POST', {
		name: body.name,
		revision: {
			skills: body.skills ?? config?.skills?.map((s: { id: string }) => s.id),
			useMemory: body.use_memory ?? config?.useMemory ?? false,
			mcpProfile: (body.mcp_profile ?? config?.mcp?.id) || undefined,
			model: body.model ?? latest?.model,
			prompt: body.system_prompt?.trim() || 'You are a helpful assistant.',
			outputMode: body.publish_artifact ? 'APPROVED_ARTIFACT' : 'CHAT',
			sandbox: body.sandbox ?? false,
		},
	});
}
export async function agents() {
	if (!signedIn()) return { agents: [], total: 0 };
	const rows = await api<
		{
			id: string;
			name: string;
			ownerId: string;
			editable: number;
			prompt: string;
			model: string;
			config: string | null;
		}[]
	>('/agents');
	const list = rows.map((a): AgentView => {
		const config = typeof a.config === 'string' ? JSON.parse(a.config) : a.config;
		return {
			id: a.id,
			user_id: a.ownerId,
			created_at: '',
			updated_at: '',
			editable: Boolean(a.editable),
			data: {
				id: a.id,
				name: a.name,
				system_prompt: a.prompt ?? '',
				model: a.model ?? undefined,
				mcp_profile: config?.mcp?.id ?? '',
				sandbox: config?.sandbox ?? false,
				skills: config?.skills?.map((skill: { id: string }) => skill.id) ?? [],
				use_memory: config?.useMemory ?? false,
				publish_artifact: config?.outputMode === 'APPROVED_ARTIFACT',
				chat_config: { context_config: {}, react_config: {}, invite_config: {} },
			},
		};
	});
	return { agents: list, total: list.length };
}
export async function sessions(agentId: string) {
	if (!signedIn()) return { sessions: [], total: 0 };
	const rows = await api<
		{
			id: string;
			ownerId: string;
			createdAt: string;
			name: string | null;
			model: string;
			status: string | null;
			permissionMode: PermissionMode;
			scheduleId: string | null;
			channelSource: string | null;
			channelThread: string | null;
			publishApproval: number | boolean;
		}[]
	>('/conversations?agentId=' + encodeURIComponent(agentId));
	const list = rows.map(
		(r): SessionView => ({
			session: {
				id: r.id,
				created_at: r.createdAt,
				updated_at: r.createdAt,
				user_id: r.ownerId,
				agent_id: agentId,
				origin: r.scheduleId ? { type: 'schedule', schedule_id: r.scheduleId }
					: r.channelSource ? {type: 'channel', channel_id: r.channelSource, chat_id: r.channelThread ?? '', chat_name: r.channelSource } : { type: 'user' },
				team_id: null,
				config: {
					name: r.name ?? format(new Date(r.createdAt), 'yyyy-MM-dd HH:mm:ss'),
					naming: { auto: !r.name },
					chat_model_config: {
						model: r.model,
						type: 'java',
						model_class: '',
						parameters: {},
						credential_id: '',
					},
					fallback_chat_model_config: null,
					tts_model_config: null,
					asr_model_config: null,
					realtime_model_config: null,
					knowledge_config: null,
					workspace_id: '',
					cwd: null,
				},
				state: { permission_mode: r.permissionMode, publish_approval: Boolean(r.publishApproval) },
			},
			status:
				r.status === 'WAITING_APPROVAL'
					? 'awaiting_permission'
					: ['RUNNING', 'QUEUED', 'CLAIMED'].includes(r.status ?? '')
						? 'running'
						: 'idle',
			team: null,
		}),
	);
	return { sessions: list, total: list.length };
}
export async function createSession(agentId: string, model?: string) {
	const latest = (await revisions(agentId))[0];
	if (!latest) throw new Error('请在 Agent 设置中发布版本。');
	const { conversationId } = await api<{ conversationId: string }>('/conversations', 'POST', {
		agentId,
		agentRevisionId: latest.id,
		model,
	});
	return { session_id: conversationId };
}
