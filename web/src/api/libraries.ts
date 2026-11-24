import { api } from './harness';

export interface Library {
	id: string;
	name: string;
	documents: number;
}
export interface LibraryDocument {
	id: string;
	name: string;
	chars: number;
}
export interface Excerpt {
	id: string;
	name: string;
	excerpt: string;
}

export const libraryList = { queryKey: ['libraries'], queryFn: () => api<Library[]>('/libraries') };
export const libraryApi = {
	create: (name: string) => api<{ id: string }>('/libraries', 'POST', { name }),
	remove: (id: string) => api<void>('/libraries/' + id, 'DELETE'),
	documents: (id: string) => api<LibraryDocument[]>('/libraries/' + id + '/documents'),
	add: (id: string, name: string, content: string) =>
		api('/libraries/' + id + '/documents', 'POST', { name, content }),
	removeDoc: (id: string, doc: string) => api('/libraries/' + id + '/documents/' + doc, 'DELETE'),
	search: (id: string, q: string) =>
		api<Excerpt[]>('/libraries/' + id + '/search?q=' + encodeURIComponent(q)),
	select: (conversation: string, libraryId: string | null) =>
		api('/conversations/' + conversation + '/library', 'PUT', { libraryId }),
};
