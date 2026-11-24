import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { BookOpen, FileText, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { libraryApi, libraryList, type Library } from '@/api/libraries';
import { DeleteDialog } from '@/components/dialog/DeleteDialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useTranslation } from '@/i18n/useI18n';

export function DocumentLibrary() {
	const { t } = useTranslation();
	const { kbId } = useParams();
	const navigate = useNavigate();
	const cache = useQueryClient();
	const list = useQuery(libraryList);
	const [name, setName] = useState('');
	const selected =
		list.data?.find((book) => book.id === kbId) ?? (!kbId ? list.data?.[0] : undefined);
	const create = useMutation({
		mutationFn: libraryApi.create,
		onSuccess: async ({ id }) => {
			await cache.invalidateQueries({ queryKey: libraryList.queryKey });
			setName('');
			navigate('/knowledge/' + id);
		},
	});
	return (
		<main className="m-2 flex flex-1 min-h-0 flex-col overflow-auto rounded-[22px] bg-card p-5 shadow-panel md:flex-row md:gap-8">
			<aside className="space-y-4 md:w-64 md:shrink-0">
				<h1 className="text-xl font-semibold">{t('common.knowledge')}</h1>
				<form
					className="flex gap-1"
					onSubmit={(e) => {
						e.preventDefault();
						if (name.trim()) create.mutate(name.trim());
					}}
				>
					<Input
						aria-label={t('harness.libraryName')}
						placeholder={t('harness.libraryName')}
						maxLength={120}
						value={name}
						onChange={(e) => setName(e.target.value)}
					/>
					<Button type="submit" disabled={!name.trim() || create.isPending}>
						{t('common.create')}
					</Button>
				</form>
				{(list.error || create.error) && (
					<p role="alert">{(list.error || create.error)?.message}</p>
				)}
				{list.isPending && <p>{t('common.loading')}</p>}
				<nav className="grid gap-1">
					{list.data?.map((book) => (
						<Button
							key={book.id}
							variant={selected?.id === book.id ? 'secondary' : 'ghost'}
							className="justify-start"
							onClick={() => navigate('/knowledge/' + book.id)}
						>
							<BookOpen />
							<span className="truncate">{book.name}</span>
							<span className="ml-auto">{book.documents}</span>
						</Button>
					))}
				</nav>
			</aside>
			{selected ? (
				<Documents
					key={selected.id}
					book={selected}
					onRemoved={() => navigate('/knowledge', { replace: true })}
				/>
			) : (
				<p className="m-auto p-6 text-muted-foreground">
					{kbId ? t('harness.libraryMissing') : t('harness.libraryStart')}
				</p>
			)}
		</main>
	);
}

function Documents({ book, onRemoved }: { book: Library; onRemoved: () => void }) {
	const { t } = useTranslation();
	const cache = useQueryClient();
	const docsKey = ['library-documents', book.id];
	const docs = useQuery({ queryKey: docsKey, queryFn: () => libraryApi.documents(book.id) });
	const [text, setText] = useState('');
	const [keyword, setKeyword] = useState('');
	const [target, setTarget] = useState<{ id?: string; name: string } | null>(null);
	const hits = useQuery({
		queryKey: ['library-search', book.id, keyword],
		queryFn: () => libraryApi.search(book.id, keyword),
		enabled: !!keyword,
	});
	const refresh = () =>
		Promise.all(
			[libraryList.queryKey, docsKey, ['library-search', book.id]].map((queryKey) =>
				cache.invalidateQueries({ queryKey }),
			),
		);
	const upload = useMutation({
		mutationFn: async (file: File) => {
			if (file.size > 262144) throw new Error(t('harness.documentLimit'));
			const content = new TextDecoder('utf-8', { fatal: true }).decode(
				await file.arrayBuffer(),
			);
			if (!content.trim() || content.includes('\0') || content.length > 65536)
				throw new Error(t('harness.documentLimit'));
			return libraryApi.add(book.id, file.name, content);
		},
		onSuccess: refresh,
	});
	return (
		<section className="min-w-0 flex-1 space-y-5 pt-5 md:pt-0">
			<div className="flex items-center justify-between">
				<h2 className="truncate text-xl font-medium">{book.name}</h2>
				<Button
					variant="ghost"
					aria-label={t('common.delete') + ' ' + book.name}
					onClick={() => setTarget({ name: book.name })}
				>
					<Trash2 />
				</Button>
			</div>
			<label className="grid gap-2 text-sm">
				{t('harness.documentUpload')}
				<Input
					type="file"
					accept=".txt,.md,.csv,.json,.yaml,.yml,.log,text/plain,text/markdown"
					disabled={upload.isPending}
					onChange={(e) => {
						const file = e.target.files?.[0];
						e.target.value = '';
						if (file) upload.mutate(file);
					}}
				/>
			</label>
			{(docs.error || upload.error || hits.error) && (
				<p role="alert">{(docs.error || upload.error || hits.error)?.message}</p>
			)}
			<ul className="divide-y">
				{docs.data?.map((doc) => (
					<li key={doc.id} className="flex items-center gap-2 py-2 text-sm">
						<FileText className="size-4" />
						<span className="truncate">{doc.name}</span>
						<span className="ml-auto text-muted-foreground">{doc.chars}</span>
						<Button
							variant="ghost"
							aria-label={t('common.delete') + ' ' + doc.name}
							onClick={() => setTarget({ id: doc.id, name: doc.name })}
						>
							<Trash2 />
						</Button>
					</li>
				))}
			</ul>
			<form
				className="flex gap-2"
				onSubmit={(e) => {
					e.preventDefault();
					setKeyword(text.trim());
				}}
			>
				<Input
					aria-label={t('harness.searchDocuments')}
					placeholder={t('harness.searchDocuments')}
					maxLength={256}
					value={text}
					onChange={(e) => setText(e.target.value)}
				/>
				<Button disabled={!text.trim()}>{t('harness.search')}</Button>
			</form>
			{keyword && hits.isFetching && <p>{t('common.loading')}</p>}
			{hits.data?.length === 0 && <p>{t('panel.search.emptyTitle')}</p>}
			{hits.data?.map((hit) => (
				<article key={hit.id} className="space-y-2 rounded-xl border p-3">
					<strong>{hit.name}</strong>
					<p className="whitespace-pre-wrap break-words text-sm">{hit.excerpt}</p>
				</article>
			))}
			<DeleteDialog
				open={!!target}
				onOpenChange={(open) => {
					if (!open) setTarget(null);
				}}
				title={t('common.delete') + ' ' + (target?.name ?? '')}
				description={
					target?.id ? t('common.deleteDescription') : t('harness.removeLibrary')
				}
				onConfirm={async () => {
					if (!target) return;
					if (target.id) await libraryApi.removeDoc(book.id, target.id);
					else await libraryApi.remove(book.id);
					if (!target.id) onRemoved();
					await refresh();
					setTarget(null);
				}}
			/>
		</section>
	);
}
