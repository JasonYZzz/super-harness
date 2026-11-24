import { useQuery } from '@tanstack/react-query';

import { api } from '@/api/harness';
import { Badge } from '@/components/ui/badge';
import { useTranslation } from '@/i18n/useI18n';

export function ServerModels() {
	const { t } = useTranslation();
	const catalog = useQuery({
		queryKey: ['tenant-chat-models'],
		queryFn: () => api<{ models: string[]; defaultModel: string }>('/tenant'),
	});
	return (
		<main className="m-2 flex-1 space-y-5 overflow-auto rounded-[22px] bg-card p-6 shadow-panel">
			<h1 className="text-2xl font-semibold">{t('credential.availableModels')}</h1>
			{catalog.isPending && <p>{t('common.loading')}</p>}
			{catalog.error && <p role="alert">{catalog.error.message}</p>}
			<ul className="divide-y">
				{catalog.data?.models.map((model) => (
					<li key={model} className="flex flex-wrap items-center gap-3 py-3">
						<span className="font-mono">{model}</span>
						{model === catalog.data.defaultModel && (
							<Badge variant="secondary">{t('harness.defaultModel')}</Badge>
						)}
					</li>
				))}
			</ul>
		</main>
	);
}
