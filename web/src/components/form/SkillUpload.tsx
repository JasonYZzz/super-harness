import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { toast } from 'sonner';

import { api } from '@/api/harness';
import { skillVersions } from '@/api/skill';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useTranslation } from '@/i18n/useI18n';

export function SkillUpload() {
	const { t } = useTranslation();
	const client = useQueryClient();
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState('');
	async function upload(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		const form = event.currentTarget;
		const data = new FormData(form);
		const file = data.get('file');
		if (!(file instanceof File) || busy) return;
		setBusy(true);
		setError('');
		try {
			if (/\.(zip|skill)$/i.test(file.name)) {
				if (file.size > 1048576) throw new Error('SKILL_ARCHIVE_TOO_LARGE');
				await api('/skills/import', 'POST', data);
			} else {
				if (file.name !== 'SKILL.md' || file.size > 32000) throw new Error('INVALID_SKILL_DOCUMENT');
				const content = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer());
				await api('/skills', 'POST', { version: data.get('version'), content });
			}
			form.reset();
			await client.invalidateQueries({ queryKey: skillVersions.queryKey });
			toast.success(t('skill.uploaded'));
		} catch (e) {
			const code = e instanceof Error ? e.message : String(e);
			setError(t('skill.uploadErrors.' + code, { defaultValue: code }));
		} finally {
			setBusy(false);
		}
	}
	return (
		<details className="m-3 rounded-lg border p-3">
			<summary className="cursor-pointer text-sm font-medium">{t('skill.upload')}</summary>
			<form className="mt-3 grid max-w-lg gap-3" onSubmit={upload}>
				<p className="text-sm text-muted-foreground">{t('skill.packageHelp')}</p>
				<label>{t('skill.fileLabel')}<Input type="file" name="file" accept=".zip,.skill,.md" required /></label>
				<label>{t('skill.versionLabel')}<Input name="version" maxLength={80} required /></label>
				{error && <p role="alert">{error}</p>}
				<Button disabled={busy} type="submit">{t('skill.publish')}</Button>
			</form>
		</details>
	);
}
