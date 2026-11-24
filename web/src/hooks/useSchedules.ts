import { useQuery, useQueryClient } from '@tanstack/react-query';

import { scheduleApi } from '@/api';
import type { CreateScheduleRequest } from '@/api';

export function useSchedules() {
	const cache = useQueryClient();
	const query = useQuery({
		queryKey: ['schedules'],
		queryFn: scheduleApi.list,
		refetchInterval: 30_000,
	});
	const refetch = () => cache.invalidateQueries({ queryKey: ['schedules'] });
	return {
		schedules: query.data?.schedules ?? [],
		loading: query.isPending,
		error: query.error,
		refetch,
		create: async (body: CreateScheduleRequest) => {
			const created = await scheduleApi.create(body);
			await refetch();
			return created;
		},
		remove: async (id: string) => {
			await scheduleApi.delete(id);
			await refetch();
		},
	};
}
