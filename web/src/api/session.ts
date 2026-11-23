import { api, sessions, createSession } from './harness';
import type { CreateSessionRequest, UpdateSessionRequest } from './types';

export const sessionApi = {
	list: sessions,
	create: (body: CreateSessionRequest) =>
		createSession(body.agent_id, body.chat_model_config?.model),
	async update(sessionId: string, agentId: string, body: UpdateSessionRequest) {
		await api('/conversations/' + sessionId, 'PATCH', { name: body.name });
		return (await sessions(agentId)).sessions.find((v) => v.session.id === sessionId)!.session;
	},
	delete: (sessionId: string) => api<void>('/conversations/' + sessionId, 'DELETE'),
};
