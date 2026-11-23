import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { api, signedIn } from '@/api/harness';
import { skillApi, skillVersions } from '@/api/skill';
import { useTranslation } from '@/i18n/useI18n';

export function useSkillManagement(enabled = true) {
	const client = useQueryClient();
	const { t } = useTranslation();
	const tenant = useQuery({
		queryKey: ['tenant-skill-role'],
		enabled: enabled && signedIn(),
		queryFn: () => api<{ role: string }>('/tenant'),
	});
	async function revoke(id: string) {
		try {
			const version = await skillApi.get(id);
			const revisions = version.usedBy.map((r) => r.agent + ' #' + r.revision).join(', ');
			if (
				!window.confirm(
					t('skill.confirmRevoke', {
						name: version.name,
						version: version.version,
						revisions: revisions || '—',
					}),
				)
			)
				return false;
			await skillApi.remove(id);
			await client.invalidateQueries({ queryKey: skillVersions.queryKey });
			return true;
		} catch (error) {
			toast.error(String(error));
			return false;
		}
	}
	return { canEdit: tenant.data?.role === 'OWNER' || tenant.data?.role === 'EDITOR', revoke };
}
