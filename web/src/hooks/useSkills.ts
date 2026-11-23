import { useQuery } from '@tanstack/react-query';

import { skillVersions, skillView } from '@/api/skill';

export function useSkills() {
	const query = useQuery({
		...skillVersions,
		select: (rows) => rows.filter((row) => !row.revoked).map(skillView),
	});
	return {
		skills: query.data ?? [],
		loading: query.isPending,
		error: query.error,
		refetch: query.refetch,
	};
}
