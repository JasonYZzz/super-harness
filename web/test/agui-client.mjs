import { HttpAgent } from '@ag-ui/client';

const agentFor = (base, token, threadId, initialMessages = []) =>
	new HttpAgent({
		url: base + '/api/v1/ag-ui/runs',
		threadId,
		initialMessages,
		headers: { Authorization: 'Bearer ' + token },
	});

export async function submitTurn(base, token, threadId, content, runId) {
	const agent = agentFor(base, token, threadId, [{ role: 'user', id: runId, content }]);
	let task;
	await agent.runAgent(
		{ runId },
		{
			onCustomEvent: ({ event }) => {
				if (event.name === 'harness.run.status' && !task) {
					task = event.value.runId;
					agent.abortRun();
				}
			},
		},
	);
	if (!task) throw new Error('Missing task admission');
	return task;
}

export async function readThread(base, token, threadId, since) {
	const agent = agentFor(base, token, threadId);
	let view;
	await agent.runAgent(
		{ forwardedProps: { 'harness.snapshot': since ? { since } : {} } },
		{
			onRunFinishedEvent: ({ event }) => {
				view = {
					...event.result,
					messages: agent.messages,
					state: agent.state,
					interrupts: event.outcome.interrupts ?? [],
				};
			},
		},
	);
	if (!view) throw new Error('Incomplete thread snapshot');
	return view;
}

export async function publishDecision(base, token, approval, approved) {
	const agent = agentFor(base, token, approval.threadId);
	let receipt;
	await agent.runAgent(
		{
			resume: [
				{
					interruptId: approval.id,
					status: 'resolved',
					payload: { approved, argsHash: approval.argsHash },
				},
			],
		},
		{
			onToolCallResultEvent: ({ event }) => {
				receipt = JSON.parse(event.content);
			},
		},
	);
	if (!receipt) throw new Error('Missing approval receipt');
	return receipt;
}
