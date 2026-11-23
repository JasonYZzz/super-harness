import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';

import { test, expect } from '@playwright/test';

const cleanup = { agent: '', conversation: '' };
test.afterEach(async ({ request }) => {
	const headers = {
		Authorization:
			'Bearer ' + execFileSync('../scripts/dev-token.sh', { encoding: 'utf8' }).trim(),
	};
	for (const [resource, id] of [
		['conversations', cleanup.conversation],
		['agents', cleanup.agent],
	]) {
		if (id)
			expect(
				(await request.delete('/api/v1/' + resource + '/' + id, { headers })).ok(),
			).toBeTruthy();
	}
	cleanup.agent = '';
	cleanup.conversation = '';
});

test('official AgentScope chat uses authenticated Java conversations, approvals and artifacts', async ({
	page, context,
}) => {
	const errors: string[] = [];
	const unexpectedApiRequests: string[] = [];
	page.on('pageerror', (e) => errors.push(e.message));
	page.on('request', (request) => {
		if (!['xhr', 'fetch', 'eventsource'].includes(request.resourceType())) return;
		const url = new URL(request.url());
		if (url.origin !== new URL(page.url()).origin || !url.pathname.startsWith('/api/v1/')) {
			unexpectedApiRequests.push(url.pathname);
		}
	});
	await page.goto('/');
	await expect(page.locator('#server-url-input')).toBeVisible();
	await page.getByLabel('用户名', {exact: true}).fill('admin');
	await page.getByLabel('密码', {exact: true}).fill('admin');
	await page.getByRole('button', { name: '开始使用' }).click();
	await expect(page).toHaveURL(/\/chat/);
	await page.getByRole('button', { name: '跳过', exact: true }).click();
	await page.locator('#tour-llm-select').click();
	await page.getByRole('menuitem', { name: '如何配置模型' }).click();
	await expect(page.getByRole('dialog')).toContainText('application.yaml');
	await page.keyboard.press('Escape');
	await page.locator('#tour-create-agent').click();
	const agent = 'UI-' + randomUUID();
	await page.locator('#agent-form-identity-name').fill(agent);
	await page.locator('#agent-form-identity-system_prompt').fill('请用中文回答。');
	await page.locator('#agent-form-identity-publish_artifact').click();
	const agentCreated = page.waitForResponse(
		(r) => r.url().endsWith('/api/v1/agents') && r.request().method() === 'POST',
	);
	await page.getByRole('dialog').getByRole('button', { name: '创建', exact: true }).click();
	const created = await agentCreated;
	cleanup.agent = (await created.json()).agentId;
	await expect(page.getByRole('dialog')).toHaveCount(0);
	const tenant = await page.request.get('/api/v1/tenant', {
		headers: { Authorization: created.request().headers().authorization },
	});
	expect(created.request().postDataJSON().revision.model).toBe((await tenant.json()).defaultModel);
	const picker = page.locator('[data-sidebar="content"] button[aria-haspopup="menu"]').first();
	if (!(await picker.innerText()).includes(agent)) {
		await picker.click();
		await page.getByRole('menuitem', { name: agent, exact: true }).click();
	}
	await page.locator('#tour-create-session').click();
	await expect(page).toHaveURL(/\/chat\/[\w-]+\/[\w-]+$/);
	const history = page.url();
	cleanup.conversation = new URL(history).pathname.split('/').at(-1)!;
	await page.locator('#tour-llm-select').click();
	const saved = page.waitForResponse(
		(r) => r.request().method() === 'PUT' && r.url().endsWith('/model'),
	);
	await page.getByRole('menuitem', { name: 'local-echo', exact: true }).click();
	expect((await saved).ok()).toBeTruthy();
	await page.reload();
	await expect(page.locator('#tour-llm-select')).toHaveText('local-echo');
	await page.getByRole('button', { name: 'Default' }).click();
	const modeSaved = page.waitForResponse(
		(r) => r.request().method() === 'PUT' && r.url().endsWith('/permission'),
	);
	await page.getByRole('menuitem', { name: 'Bypass', exact: true }).click();
	expect((await modeSaved).ok()).toBeTruthy();
	await page.reload();
	await expect(page.getByRole('button', { name: 'Bypass' })).toBeVisible();
	const composer = page.getByPlaceholder('输入消息...');
	let closed = false,
		reconnected = false;
	await page.route('**/api/v1/ag-ui/runs', async (route) => {
		if (Object.keys(route.request().postDataJSON().forwardedProps ?? {}).length) return route.continue();
		if (closed) {
			reconnected = true;
			return route.continue();
		}
		closed = true;
		// Lose the response after the server has accepted the command.
		await route.fetch();
		await route.fulfill({ contentType: 'text/event-stream', body: ': disconnected\n\n' });
	});
	for (const text of ['第一轮 **回放**', '第二轮 **回答**']) {
		await composer.fill(text);
		await composer.press('Enter');
		const approval = page.getByRole('button', { name: /1\. 是/ });
		await expect(approval).toBeVisible();
		if (text.startsWith('第一')) {
			const other = await context.newPage();
			await other.addInitScript(token => sessionStorage.setItem('harness.access', token),
				created.request().headers().authorization.slice(7));
			await other.goto(history);
			await other.getByRole('button', { name: /1\. 是/ }).click();
			await expect(other.getByPlaceholder('输入消息...')).toBeEnabled();
			await other.close();
		} else {
			await approval.click();
		}
		await expect(page.locator('[data-role="assistant"]').last()).toContainText(
			text.includes('第一') ? '回放' : '回答',
		);
		await expect(page.getByRole('button', { name: /1\. 是/ })).toHaveCount(0);
	}
	expect(closed && reconnected).toBeTruthy();
	await page.reload();
	await expect(page).toHaveURL(history);
	await expect(page.locator('[data-role="assistant"]')).toHaveCount(2);
	await expect(
		page
			.locator('[data-role="assistant"]')
			.last()
			.locator('[data-streamdown="strong"]')
			.filter({ hasText: /^回答$/ }),
	).toBeVisible();
	await page.getByRole('button', { name: '面板' }).click();
	const planMenu = page.getByRole('menuitemcheckbox', { name: '计划', exact: true });
	if ((await planMenu.getAttribute('aria-checked')) !== 'true') await planMenu.click();
	await page.keyboard.press('Escape');
	await expect(page.getByText('暂无任务', { exact: true })).toBeVisible();
	await page.getByRole('button', { name: '面板' }).click();
	const runsMenu = page.getByRole('menuitemcheckbox', { name: '运行记录', exact: true });
	if ((await runsMenu.getAttribute('aria-checked')) !== 'true') await runsMenu.click();
	await page.keyboard.press('Escape');
	await page.reload();
	await expect(page.getByText('暂无任务', { exact: true })).toBeVisible();
	await page.getByRole('button', { name: /第二轮.*SUCCEEDED/ }).click();
	const download = page.waitForEvent('download');
	await page.getByRole('button', { name: /下载 response.txt/ }).click();
	expect((await download).suggestedFilename()).toBe('response.txt');
	await page.setViewportSize({ width: 390, height: 844 });
	expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
		390,
	);
	expect(errors).toEqual([]);
	expect(unexpectedApiRequests).toEqual([]);
});

test('all official sidebar routes and direct reloads use the Java origin', async ({ page }) => {
	const errors: string[] = [];
	const requests: string[] = [];
	page.on('pageerror', (e) => errors.push(e.message));
	page.on('request', (r) => {
		if (['xhr', 'fetch', 'eventsource'].includes(r.resourceType())) requests.push(r.url());
	});
	await page.goto('/knowledge');
	await expect(page.locator('#username-input')).toBeVisible();
	await page.getByRole('button', {name: '使用访问令牌'}).click();
	await page
		.locator('#username-input')
		.fill(execFileSync('../scripts/dev-token.sh', { encoding: 'utf8' }).trim());
	await page.getByRole('button', { name: '开始使用' }).click();
	await expect(page).toHaveURL(/\/knowledge$/);
	await page.evaluate(() => localStorage.setItem('chat_tour_done', '1'));
	const routes = [
		'chat',
		'schedule',
		'channel',
		'credential',
		'mcp',
		'skill',
		'knowledge',
		'voice-profile',
	];
	for (const [i, route] of routes.entries()) {
		await page
			.locator('[data-slot="sidebar"]')
			.first()
			.locator('[data-sidebar="content"] [data-sidebar="menu-button"]')
			.nth(i)
			.click();
		await expect(page).toHaveURL(new RegExp('/' + route + '(?:/|$)'));
		const entry = await page.reload();
		expect(entry?.status()).toBe(200);
		await expect(page.locator('[data-slot="sidebar"]').first()).toBeVisible();
		if (route !== 'chat') await expect(page.getByRole('note')).toBeVisible();
	}
	const origin = new URL(page.url()).origin;
	expect(requests.filter((url) => !url.startsWith(origin + '/api/v1/'))).toEqual([]);
	expect(errors).toEqual([]);
});

test('model-created tasks open the plan panel and survive a second turn and reload', async ({
	page,
}) => {
	const address = process.env.SH_TEST_PLAN_PATH;
	test.skip(
		!address,
		'The isolated acceptance server provides the deterministic planning model.',
	);
	const token = execFileSync('../scripts/dev-token.sh', { encoding: 'utf8' }).trim();
	await page.addInitScript((value) => {
		localStorage.setItem('chat_tour_done', '1');
		sessionStorage.setItem('harness.access', value);
	}, token);
	await page.goto(address!);
	const composer = page.getByPlaceholder('输入消息...');
	await composer.fill('plan-fixture');
	await composer.press('Enter');
	await expect(page.getByText('Inspect reference behavior', { exact: true })).toBeVisible();
	await expect(page.locator('[data-role="assistant"]').last()).toContainText('provider fixture');
	await composer.fill('update-plan-fixture');
	await composer.press('Enter');
	await expect(page.getByText('Inspect reference behavior', { exact: true })).toHaveClass(
		/line-through/,
	);
	await page.reload();
	await expect(page.getByText('Inspect reference behavior', { exact: true })).toHaveClass(
		/line-through/,
	);
});
