import { api } from './harness';
import type { CreateScheduleRequest, CreateScheduleResponse, ScheduleListResponse } from './types';

export interface ScheduledSession {
	id: string;
	created_at: string;
	status: string | null;
}
export const scheduleApi = {
	list: () => api<ScheduleListResponse>('/schedules'),
	create: ({ chat_model_config, ...body }: CreateScheduleRequest) =>
		api<CreateScheduleResponse>('/schedules', 'POST', {
			...body,
			model: chat_model_config.model,
		}),
	activate: (id: string, enabled: boolean) => api<void>('/schedules/' + id, 'PATCH', { enabled }),
	delete: (id: string) => api<void>('/schedules/' + id, 'DELETE'),
	listSessions: (id: string) =>
		api<{ sessions: ScheduledSession[] }>('/schedules/' + id + '/sessions'),
};
