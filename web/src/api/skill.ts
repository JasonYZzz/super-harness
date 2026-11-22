import { api } from './harness';
import type { SkillRecord, SkillView } from './types';

interface SkillVersion {
	id: string;
	name: string;
	version: string;
	revoked: boolean;
}
function view(row: SkillVersion): SkillView {
	return {
		id: row.id,
		name: row.name,
		version: row.version,
		enabled: !row.revoked,
		display_name: row.name,
		description: '',
		tags: [],
		author: null,
		icon_url: null,
		url: null,
		hub_id: null,
		card_id: null,
	};
}
export const skillApi = {
	list: async () =>
		(await api<SkillVersion[]>('/skills')).filter((row) => !row.revoked).map(view),
	get: async (id: string): Promise<SkillRecord> => {
		const row = await api<SkillVersion & { content: string }>(
			'/skills/' + encodeURIComponent(id),
		);
		return { ...view(row), markdown: row.content };
	},
	remove: (id: string) => api<void>('/skills/' + encodeURIComponent(id), 'DELETE'),
};
