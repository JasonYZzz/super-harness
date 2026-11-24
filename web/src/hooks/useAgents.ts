import { useQuery, useQueryClient } from '@tanstack/react-query';

import { agentApi } from '../api';
import type { CreateAgentRequest, UpdateAgentRequest } from '../api';

const key = ['agents'];
export function useAgents() {
	const cache = useQueryClient();
	const query = useQuery({ queryKey: key, queryFn: agentApi.list });
	const refetch = () => cache.invalidateQueries({ queryKey: key });
	return {
		agents: query.data?.agents ?? [],
		loading: query.isPending,
		error: query.error,
		refetch,
		create: async (body: CreateAgentRequest) => {
			const created = await agentApi.create(body);
			await refetch();
			return created;
		},
		update: async (id: string, body: UpdateAgentRequest) => {
			const updated = await agentApi.update(id, body);
			await refetch();
			return updated;
		},
		remove: async (id: string) => {
			await agentApi.delete(id);
			await refetch();
		},
	};
}
