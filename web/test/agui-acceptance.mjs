// Run by scripts/acceptance.mjs against its disposable API, Worker and MySQL.
import { readThread } from './agui-client.mjs';
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
	assert.equal((await readThread(base, token, threadId)).turns.length, 1);
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
	for (const forwardedProps of [
		{ unknown: {} },
		{ 'harness.snapshot': { since: 'invalid' } },
		{ 'harness.observe': 'invalid' },
		{ 'harness.snapshot': {}, 'harness.observe': randomUUID() },
	]) {
		assert.equal(
			(await post({ threadId, runId: randomUUID(), messages: [], forwardedProps })).status,
			400,
		);
	}
	const agent = new HttpAgent({ url, threadId, headers, initialMessages: input.messages });
	await agent.runAgent({ runId: input.runId });
	agent.addMessage({ role: 'user', id: randomUUID(), content: '第二轮' });
	await agent.runAgent({ runId: randomUUID() });
	assert.match(agent.messages.at(-1).content, /第二轮/);

	// Spring Security denies unmapped console URLs before MVC routing.
	const retiredPage = await fetch(base + '/ag-ui/' + threadId, { headers });
	assert.equal(retiredPage.status, 403);
	for (const [method, path] of [
		['GET', '/api/v1/ag-ui/threads/' + threadId],
		['POST', '/api/v1/ag-ui/threads/' + threadId + '/runs/' + randomUUID() + '/connect'],
		['POST', '/api/v1/runs'],
		['POST', '/api/v1/runs/' + randomUUID() + '/cancel'],
		['POST', '/api/v1/runs/' + randomUUID() + '/resume'],
		['POST', '/api/v1/approvals/' + randomUUID() + '/decision'],
		['GET', '/api/v1/conversations/' + threadId + '/messages'],
	]) {
		const removed = await fetch(base + path, { method, headers });
		assert.ok([404, 405].includes(removed.status), method + ' ' + path + ': ' + removed.status);
	}
	// Cancelling a completed task acknowledges the operation without creating another task.
	const completedTask = (await readThread(base, token, threadId)).turns.at(-1).runId;
	const stop = {
		threadId,
		runId: randomUUID(),
		messages: [],
		forwardedProps: { 'harness.cancel': completedTask },
	};
	assert.deepEqual((await frames(stop)).at(-1).result, { taskId: completedTask });
	const controller = new HttpAgent({ url, threadId, headers });
	const controlAck = await controller.runAgent({ forwardedProps: stop.forwardedProps });
	assert.equal(controlAck.result.taskId, completedTask);
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
		const snapshotOf = (since) => readThread(base, token, publish, since);
		const snapshot = await snapshotOf();
		assert.deepEqual(snapshot.interrupts, [interrupt]);
		assert.equal(snapshot.turns.at(-1).interactionId, a.runId);
		assert.equal(snapshot.capabilities.humanInTheLoop.interrupts, true);
		assert.deepEqual(
			snapshot.messages,
			before.findLast((e) => e.type === 'MESSAGES_SNAPSHOT').messages,
		);
		assert.equal((await snapshotOf(snapshot.cursor)).unchanged, true);
		const reader = new HttpAgent({ url, threadId: publish, headers });
		const forwardedProps = { 'harness.observe': run };
		await reader.runAgent({ forwardedProps });
		assert.deepEqual(reader.messages, snapshot.messages);
		assert.equal((await post({ threadId, runId: randomUUID(), forwardedProps })).status, 404);
		assert.equal((await post({ ...a, forwardedProps })).status, 400);
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
			decision.approved = true;
			await frames({ ...b, runId: randomUUID() });
		}
		assert.equal((await frames({ ...b, threadId })).at(-1).code, 'INVALID_INTERRUPT');
		const callsBefore = (await api('/runs/' + run + '/trace')).modelCalls;
		const [result, repeated] = await Promise.all([
			frames(b),
			frames(b),
			mode === 'approve' ? frames({ ...b, runId: randomUUID() }) : undefined,
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
		const closed = await snapshotOf(snapshot.cursor);
		assert.deepEqual(closed.interrupts, []);
		const receipts = closed.messages.filter(
			(m) => m.role === 'tool' && m.toolCallId === interrupt.toolCallId,
		);
		assert.equal(receipts.length, 1);
		assert.equal(receipts[0].content, result.find((e) => e.type === 'TOOL_CALL_RESULT').content);
		const published = mode === 'approve' || mode === 'external';
		if (!published)
			assert.deepEqual(result.findLast((e) => e.type === 'STATE_SNAPSHOT')?.snapshot, closed.state);
		assert.equal(
			await status(),
			published ? 'SUCCEEDED' : mode === 'cancel' ? 'CANCELLED' : 'FAILED',
		);
		assert.equal(result.at(-1).type, published ? 'RUN_FINISHED' : 'RUN_ERROR');
		assert.equal((await api('/runs/' + run + '/artifacts')).length, published ? 1 : 0);
	}
	const parked = await frames({
		threadId: publish,
		runId: randomUUID(),
		messages: [{ id: randomUUID(), role: 'user', content: 'cancel platform task' }],
	});
	const taskId = parked.find((e) => e.name === 'harness.run.status').value.runId;
	const cancel = {
		threadId: publish,
		runId: randomUUID(),
		messages: [],
		forwardedProps: { 'harness.cancel': taskId },
	};
	assert.equal((await post({ ...cancel, threadId })).status, 404);
	const ack = await frames(cancel);
	assert.deepEqual(ack.at(-1).result, { taskId });
	assert.deepEqual(await frames(cancel), ack);
	assert.equal((await api('/runs/' + taskId)).status, 'CANCELLED');
	console.log(
		'PASS AG-UI real HttpAgent turns, idempotent streams, normalized input, approval, rejection, cancellation, external decisions and replay',
	);
}
