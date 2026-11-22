import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { api, signedIn } from '@/api/harness';
import { ResourcePanel } from '@/components/hub/ResourcePanel';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Item, ItemTitle, ItemContent, ItemDescription, ItemActions } from '@/components/ui/item';
import { Textarea } from '@/components/ui/textarea';

export function HarnessResources({ skills }: { skills: boolean }) {
	const path = skills ? '/skills' : '/memory',
		title = skills ? 'Skills' : '个人记忆';
	const [name, setName] = useState(''),
		[version, setVersion] = useState(''),
		[content, setContent] = useState(''),
		[search, setSearch] = useState(''),
		[busy, setBusy] = useState(false);
	const nav = useNavigate();
	const rows = useQuery({
		queryKey: ['harness-resources', path],
		enabled: signedIn(),
		queryFn: () =>
			api<{ id: string; name?: string; version?: string; content?: string; revoked?: boolean }[]>(
				path,
			),
	});
	async function save() {
		setBusy(true);
		try {
			await api(path, 'POST', skills ? { name, version, content } : { text: content });
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
						<form
							className="grid max-w-lg gap-2 p-2"
							onSubmit={(e) => {
								e.preventDefault();
								void save();
							}}
						>
							{skills && (
								<>
									<Input
										aria-label="名称"
										placeholder="名称"
										value={name}
										onChange={(e) => setName(e.target.value)}
										required
									/>
									<Input
										aria-label="版本"
										placeholder="版本"
										value={version}
										onChange={(e) => setVersion(e.target.value)}
										required
									/>
								</>
							)}
							<Textarea
								aria-label="内容"
								value={content}
								onChange={(e) => setContent(e.target.value)}
								required
							/>
							<Button disabled={busy}>{skills ? '发布版本' : '保存记忆'}</Button>
						</form>
						{rows.error && <p role="alert">{rows.error.message}</p>}
						{rows.data
							?.filter((r) =>
								(r.name ?? r.content ?? '').toLowerCase().includes(search.toLowerCase()),
							)
							.map((r) => (
								<Item key={r.id}>
									<ItemContent>
										<ItemTitle>{r.name ?? r.content}</ItemTitle>
										<ItemDescription>{r.version}</ItemDescription>
									</ItemContent>
									<ItemActions>
										<Button
											variant="outline"
											disabled={r.revoked}
											onClick={async () => {
												if (window.confirm(skills ? '撤销这个 Skill 版本？' : '删除这条记忆？'))
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
								</Item>
							))}
					</>
				)}
			</ResourcePanel>
		</main>
	);
}
