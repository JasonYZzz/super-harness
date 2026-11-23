import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { api, signedIn } from '@/api/harness';
import { skillVersions } from '@/api/skill';
import { ResourcePanel } from '@/components/hub/ResourcePanel';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Item, ItemTitle, ItemContent, ItemDescription, ItemActions } from '@/components/ui/item';
import { Textarea } from '@/components/ui/textarea';
import { useSkillManagement } from '@/hooks/useSkillManagement';

export function HarnessResources({ skills }: { skills: boolean }) {
	const path = skills ? '/skills' : '/memory',
		title = skills ? '租户技能版本' : '个人记忆';
	const [name, setName] = useState(''),
		[version, setVersion] = useState(''),
		[content, setContent] = useState(''),
		[search, setSearch] = useState(''),
		[busy, setBusy] = useState(false);
	const nav = useNavigate();
	const { canEdit, revoke } = useSkillManagement(skills);
	const [folder, setFolder] = useState<FileList | null>(null);
	const [description, setDescription] = useState('');
	const rows = useQuery({
		queryKey: skills ? skillVersions.queryKey : ['harness-resources', path],
		enabled: signedIn(),
		queryFn: () =>
			api<{ id: string; name?: string; version?: string; content?: string; revoked?: boolean }[]>(
				path,
			),
	});
	async function save() {
		setBusy(true);
		try {
			if (skills && folder?.length) {
				const files = Array.from(folder);
				const filename = (f: File) => f.webkitRelativePath.split('/').slice(1).join('/');
				const document = files.find((f) => filename(f) === 'SKILL.md');
				if (!document || files.length > 17)
					throw new Error('请选择含 SKILL.md 和最多 16 个参考文件的目录');
				const resources: Record<string, string> = {};
				const decoder = new TextDecoder('utf-8', { fatal: true });
				for (const file of files) {
					if (file === document) continue;
					if (!filename(file).startsWith('references/') || file.size > 262144) {
						throw new Error('仅支持 references/ 下的 UTF-8 文本文件，每个最多 65536 字符');
					}
					resources[filename(file)] = decoder.decode(await file.arrayBuffer());
				}
				if (document.size > 32000) throw new Error('SKILL.md 最多 8000 字符');
				await api(path, 'POST', {
					version,
					content: decoder.decode(await document.arrayBuffer()),
					resources,
				});
			} else {
				await api(
					path,
					'POST',
					skills ? { name, version, description, content } : { text: content },
				);
			}
			setContent('');
			await rows.refetch();
		} catch (e) {
			toast.error(String(e));
		} finally {
			setBusy(false);
		}
	}
	return (
		<main className="m-2 flex-1 overflow-hidden rounded-[22px] bg-card shadow-panel">
			<ResourcePanel
				title={title}
				search={{ value: search, onChange: setSearch, placeholder: '搜索' }}
			>
				{!signedIn() ? (
					<Button onClick={() => nav('/setup')}>连接 Java 工作区</Button>
				) : (
					<>
						{(!skills || canEdit) && (
							<form
								className="grid max-w-lg gap-2 p-2"
								onSubmit={(e) => {
									e.preventDefault();
									void save();
								}}
							>
								{skills && (
									<>
										<label>
											导入技能目录（SKILL.md 和可选 references/）
											<input
												type="file"
												ref={(input) => {
													input?.setAttribute('webkitdirectory', '');
												}}
												onChange={(e) => setFolder(e.target.files)}
											/>
										</label>
										{!folder?.length && (
											<Input
												aria-label="名称"
												value={name}
												onChange={(e) => setName(e.target.value)}
												placeholder="名称（完整 SKILL.md 可留空）"
											/>
										)}
										{!folder?.length && (
											<Input
												aria-label="适用场景"
												placeholder="适用场景（何时使用此技能）"
												maxLength={1024}
												value={description}
												onChange={(e) => setDescription(e.target.value)}
											/>
										)}
										<Input
											aria-label="版本"
											placeholder="版本"
											value={version}
											onChange={(e) => setVersion(e.target.value)}
											required
										/>
									</>
								)}
								{(!skills || !folder?.length) && (
									<Textarea
										aria-label="内容"
										value={content}
										onChange={(e) => setContent(e.target.value)}
										required
									/>
								)}
								<Button disabled={busy}>{skills ? '发布版本' : '保存记忆'}</Button>
							</form>
						)}
						{rows.error && <p role="alert">{rows.error.message}</p>}
						{rows.data
							?.filter((r) =>
								(r.name ?? r.content ?? '').toLowerCase().includes(search.toLowerCase()),
							)
							.map((r) => (
								<Item key={r.id}>
									<ItemContent>
										<ItemTitle>{r.name ?? r.content}</ItemTitle>
										<ItemDescription>
											{r.version}
											{r.revoked ? ' · 已撤销' : ''}
										</ItemDescription>
									</ItemContent>
									{(!skills || canEdit) && (
										<ItemActions>
											<Button
												variant="outline"
												disabled={r.revoked}
												onClick={async () => {
													if (skills) {
														await revoke(r.id);
														return;
													}
													if (window.confirm('删除这条记忆？'))
														try {
															await api(path + '/' + r.id, 'DELETE');
															await rows.refetch();
														} catch (e) {
															toast.error(String(e));
														}
												}}
											>
												{skills ? '撤销' : '删除'}
											</Button>
										</ItemActions>
									)}
								</Item>
							))}
					</>
				)}
			</ResourcePanel>
		</main>
	);
}
