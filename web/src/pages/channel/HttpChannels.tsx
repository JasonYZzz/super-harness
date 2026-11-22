import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';

import { api } from '@/api/harness';

interface Thread {
	agentId: string;
	conversationId: string;
	source: string;
	threadId: string;
}

export function HttpChannels() {
	const threads = useQuery({
		queryKey: ['http-channel-threads'],
		queryFn: () => api<Thread[]>('/channels/http/threads'),
		refetchInterval: 10000,
	});
	return (
		<main className="m-2 flex-1 space-y-5 overflow-auto rounded-[22px] bg-card p-6 shadow-panel">
			<h1 className="text-2xl font-semibold">HTTP 消息频道</h1>
			<p>外部应用可携带访问令牌向此地址投递消息。同一来源、Agent 和线程的消息进入同一个聊天会话。</p>
			<code className="block break-all">POST {window.location.origin}/api/v1/channels/http/messages</code>
			<pre className="overflow-auto rounded-lg bg-muted p-3">{JSON.stringify({
				agentId: '已发布的 Agent ID', source: 'my-app', threadId: 'thread-1',
				messageId: 'msg-1', text: '你好',
			}, null, 2)}</pre>
			<h2 className="font-semibold">我的频道会话</h2>
			{threads.isError && <p role="alert">{String(threads.error)}</p>}
			{threads.isPending && <p>正在加载…</p>}
			{threads.data?.length === 0 && <p>收到首条消息后，会话将显示在这里。</p>}
			{threads.data?.map(thread => <Link key={thread.conversationId}
				className="block underline" to={'/chat/' + thread.agentId + '/' + thread.conversationId}>
				{thread.source} / {thread.threadId}
			</Link>)}
		</main>
	);
}
