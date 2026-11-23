import { api } from './harness';
import type { SkillRecord, SkillView } from './types';

interface SkillVersion {
	id: string;
	name: string;
	version: string;
	revoked: boolean;
	description: string | null;
	author: string;
}
export const skillVersions = {
	queryKey: ['tenant-skill-versions'],
	queryFn: () => api<SkillVersion[]>('/skills'),
};

export function skillView(row: SkillVersion): SkillView {
	return {
		id: row.id,
		name: row.name,
		version: row.version,
		enabled: !row.revoked,
		display_name: row.name,
		description: row.description ?? row.name,
		tags: [],
		author: row.author,
		icon_url: null,
		url: null,
		hub_id: null,
		card_id: null,
	};
}
export const skillApi = {
	get: async (
		id: string,
	): Promise<SkillRecord & { usedBy: { agent: string; revision: number }[] }> => {
		const row = await api<
			SkillVersion & { content: string; usedBy: { agent: string; revision: number }[] }
		>('/skills/' + encodeURIComponent(id));
		return { ...skillView(row), markdown: row.content, usedBy: row.usedBy };
	},
	remove: (id: string) => api<void>('/skills/' + encodeURIComponent(id), 'DELETE'),
};
