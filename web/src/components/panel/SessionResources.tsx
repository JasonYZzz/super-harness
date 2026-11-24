import { useQuery, useQueryClient, useMutation } from '@tanstack/react-query';
import { Link } from 'react-router-dom';

import { api } from '@/api/harness';
import { libraryApi, libraryList } from '@/api/libraries';
import { useTranslation } from '@/i18n/useI18n';

interface Resources {
	revision: number;
	skills: { name: string; version: string; description: string }[];
	mcp?: { id: string; version: string; tools: string[] };
	libraryId: string | null;
}

export function SessionResources({
	sessionId,
	panel,
}: {
	sessionId: string | null;
	panel: 'skill' | 'mcp' | 'knowledge';
}) {
	const { t } = useTranslation();
	const cache = useQueryClient();
	const key = ['conversation-resources', sessionId];
	const resources = useQuery({
		queryKey: key,
		queryFn: () => api<Resources>('/conversations/' + sessionId + '/resources'),
		enabled: !!sessionId,
	});
	const libraries = useQuery({ ...libraryList, enabled: !!sessionId && panel === 'knowledge' });
	const change = useMutation({
		mutationFn: (id: string) => libraryApi.select(sessionId!, id || null),
		onSuccess: () => cache.invalidateQueries({ queryKey: key }),
	});
	const data = resources.data;
	return (
		<section className="space-y-3 p-3 text-sm">
			{!sessionId && <p>{t('harness.selectSession')}</p>}
			{sessionId && resources.isPending && <p>{t('common.loading')}</p>}
			{(resources.error || change.error || (panel === 'knowledge' && libraries.error)) && (
				<p role="alert">{(resources.error || change.error || libraries.error)?.message}</p>
			)}
			{data && panel === 'skill' && (
				<>
					<p className="text-muted-foreground">
						{t('harness.pinnedRevision', { revision: data.revision })}
					</p>
					{data.skills.length ? (
						data.skills.map((skill) => (
							<div key={skill.name} className="rounded-lg border p-2">
								<strong>
									{skill.name} · {skill.version}
								</strong>
								<p>{skill.description}</p>
							</div>
						))
					) : (
						<p>{t('harness.noSkill')}</p>
					)}
				</>
			)}
			{data && panel === 'mcp' && (
				<>
					<p className="text-muted-foreground">
						{t('harness.pinnedRevision', { revision: data.revision })}
					</p>
					{data.mcp ? (
						<>
							<strong>
								{data.mcp.id} · {data.mcp.version}
							</strong>
							<ul>
								{data.mcp.tools.map((tool) => (
									<li key={tool}>{tool}</li>
								))}
							</ul>
						</>
					) : (
						<p>{t('harness.noMcp')}</p>
					)}
				</>
			)}
			{data && panel === 'knowledge' && (
				<label className="grid gap-2">
					{t('harness.sessionLibrary')}
					<select
						className="rounded-md border bg-card p-2"
						value={data.libraryId ?? ''}
						disabled={change.isPending || libraries.isPending}
						onChange={(e) => change.mutate(e.target.value)}
					>
						<option value="">{t('harness.noLibrary')}</option>
						{libraries.data?.map((b) => (
							<option key={b.id} value={b.id}>
								{b.name}
							</option>
						))}
					</select>
				</label>
			)}
			<Link className="block underline" to={'/' + panel}>
				{t(
					panel === 'skill'
						? 'harness.manageSkills'
						: panel === 'mcp'
							? 'common.mcp-hub'
							: 'common.knowledge',
				)}
			</Link>
		</section>
	);
}
