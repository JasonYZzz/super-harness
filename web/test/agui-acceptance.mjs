// Run by scripts/acceptance.mjs against its disposable API, Worker and MySQL.
import { HttpAgent } from '@ag-ui/client';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

export async function aguiAcceptance({ base, token, api, conversation, agentId }) {
	const url = base + '/api/v1/ag-ui/runs',
		headers = { Authorization: 'Bearer ' + token };
	const echo = { prompt: 'AG-UI test', model: 'local-echo' };
	async function post(input) {
		return fetch(url, {
			method: 'POST',
			headers: { ...headers, 'Content-Type': 'application/json', Accept: 'text/event-stream' },
			body: JSON.stringify(input),
			signal: AbortSignal.timeout(30000),
		});
	}
	async function frames(input) {
		const r = await post(input);
		assert.equal(r.status, 200, await (!r.ok ? r.text() : ''));
		return (await r.text())
			.split('\n')
			.filter((l) => l.startsWith('data:'))
			.map((l) => JSON.parse(l.slice(5)));
	}
	const threadId = await conversation(agentId, echo);
	const input = {
		threadId,
		runId: randomUUID(),
		messages: [{ role: 'user', id: randomUUID(), content: '第一轮' }],
	};
	const [one, two] = await Promise.all([frames(input), frames(input)]);
	assert.deepEqual(one, two);
	assert.equal(one.at(-1).outcome.type, 'success');
	assert.equal((await api('/conversations/' + threadId + '/messages')).length, 1);
	assert.equal(
		(await post({ ...input, messages: [{ ...input.messages[0], content: 'conflict' }] })).status,
		409,
	);
	assert.deepEqual(
		await frames({
			...input,
			messages: [
				{
					...input.messages[0],
					content: [
						{ type: 'text', text: '第' },
						{ type: 'text', text: '一轮' },
					],
				},
			],
		}),
		one,
	);
	for (const extra of [
		{ tools: [{ name: 'client_tool' }] },
		{ messages: [{ id: 'u', role: 'tool', content: 'no' }] },
		{ messages: [{ id: 'u', role: 'user', content: [{ type: 'image' }] }] },
	]) {
		assert.equal((await post({ ...input, ...extra })).status, 400);
	}
	const agent = new HttpAgent({ url, threadId, headers, initialMessages: input.messages });
	await agent.runAgent({ runId: input.runId });
	agent.addMessage({ role: 'user', id: randomUUID(), content: '第二轮' });
	await agent.runAgent({ runId: randomUUID() });
	assert.match(agent.messages.at(-1).content, /第二轮/);

	const plan = await conversation(agentId, { prompt: 'test', model: 'openai:fixture' });
	const planning = {
		threadId: plan,
		runId: randomUUID(),
		messages: [{ id: randomUUID(), role: 'user', content: 'plan-fixture' }],
	};
	const planned = await frames(planning);
	assert.equal(
		planned.find((e) => e.type === 'STATE_SNAPSHOT').snapshot.tasks[0].subject,
		'Inspect reference behavior',
	);
	const usage = planned.filter((e) => e.name === 'token_usage');
	assert.ok(usage.length >= 2);
	assert.equal(
		usage.at(-1).value.cumulative.totalTokens,
		usage.reduce((sum, e) => sum + e.value.delta.totalTokens, 0),
	);
	assert.deepEqual(await frames(planning), planned);
	const publish = await conversation(agentId, { ...echo, outputMode: 'APPROVED_ARTIFACT' });
	for (const mode of ['approve', 'reject', 'cancel', 'external']) {
		const a = {
			threadId: publish,
			runId: randomUUID(),
			messages: [{ role: 'user', id: randomUUID(), content: 'publish ' + mode }],
		};
		const before = await frames(a);
		const interrupt = before.at(-1).outcome.interrupts[0];
		assert.equal(interrupt.reason, 'tool_call');
		assert.equal(before.find((e) => e.type === 'TOOL_CALL_START').toolCallId, interrupt.toolCallId);
		const run = before.find((e) => e.name === 'harness.run.status').value.runId;
		const status = async () => (await api('/runs/' + run)).status;
		assert.equal(await status(), 'WAITING_APPROVAL');
		assert.equal((await frames({ ...a, runId: randomUUID() })).at(-1).code, 'INTERRUPT_PENDING');
		const decision = { approved: mode === 'approve', argsHash: interrupt.metadata.argsHash };
		const b = {
			threadId: publish,
			runId: randomUUID(),
			messages: [],
			resume: [{ interruptId: interrupt.id, status: 'resolved', payload: decision }],
		};
		for (const payload of [
			{ ...decision, editedArgs: {} },
			{ ...decision, argsHash: '0'.repeat(64) },
		]) {
			assert.equal(
				(await frames({ ...b, resume: [{ ...b.resume[0], payload }] })).at(-1).type,
				'RUN_ERROR',
			);
			assert.equal(await status(), 'WAITING_APPROVAL');
		}
		assert.equal(
			(await frames({ ...b, resume: [{ ...b.resume[0], editedArgs: {} }] })).at(-1).code,
			'INVALID_RESUME_PAYLOAD',
		);
		if (mode === 'cancel') b.resume = [{ interruptId: interrupt.id, status: 'cancelled' }];
		if (mode === 'external') {
			await api('/approvals/' + interrupt.id + '/decision', {
				approve: true,
				argsHash: decision.argsHash,
			});
			decision.approved = true;
		}
		assert.equal((await frames({ ...b, threadId })).at(-1).code, 'INVALID_INTERRUPT');
		const callsBefore = (await api('/runs/' + run + '/trace')).modelCalls;
		const [result, repeated] = await Promise.all([
			frames(b),
			frames(b),
			mode === 'approve'
				? api('/approvals/' + interrupt.id + '/decision', {
						approve: true,
						argsHash: decision.argsHash,
					})
				: undefined,
		]);
		assert.deepEqual((await api('/runs/' + run + '/trace')).modelCalls, callsBefore);
		assert.deepEqual(result, repeated);
		assert.equal(
			result.find((e) => e.type === 'TOOL_CALL_RESULT').toolCallId,
			interrupt.toolCallId,
		);
		assert.deepEqual(await frames(b), result);
		assert.deepEqual(await frames(a), before);
		if (mode !== 'cancel') {
			const conflict = {
				...b,
				resume: [{ ...b.resume[0], payload: { ...decision, approved: !decision.approved } }],
			};
			assert.equal((await frames(conflict)).at(-1).code, 'APPROVAL_CLOSED');
		}
		const published = mode === 'approve' || mode === 'external';
		assert.equal(
			await status(),
			published ? 'SUCCEEDED' : mode === 'cancel' ? 'CANCELLED' : 'FAILED',
		);
		assert.equal(result.at(-1).type, published ? 'RUN_FINISHED' : 'RUN_ERROR');
		assert.equal((await api('/runs/' + run + '/artifacts')).length, published ? 1 : 0);
	}
	console.log(
		'PASS AG-UI real HttpAgent turns, idempotent streams, normalized input, approval, rejection, cancellation, external decisions and replay',
	);
}
