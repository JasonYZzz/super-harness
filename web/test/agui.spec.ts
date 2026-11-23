import { execFileSync } from 'node:child_process';

import { test, expect } from '@playwright/test';

const path = process.env.SH_TEST_PUBLISH_PATH;
test.beforeEach(async ({ context, page }) => {
	test.skip(!path, 'Requires an isolated publish_text conversation.');
	await context.addInitScript(
		(token) => {
			sessionStorage.setItem('harness.access', token);
			localStorage.setItem('chat_tour_done', '1');
		},
		execFileSync('../scripts/dev-token.sh', { encoding: 'utf8' }).trim(),
	);
	await page.goto(path!);
});

test('a fresh tab approves an interrupted run on the same chat URL', async ({ page, context }) => {
	const input = page.getByPlaceholder('输入消息...');
	await input.fill('浏览器恢复验证');
	await input.press('Enter');
	const approve = page.getByRole('button', { name: /1\. 是/ });
	await expect(approve).toBeVisible();
	const fresh = await context.newPage();
	await fresh.goto(path!);
	await fresh.getByRole('button', { name: /1\. 是/ }).click();
	await expect(fresh.getByPlaceholder('输入消息...')).toBeEnabled();
	await fresh.close();
	await expect(approve).toHaveCount(0);
	await expect(input).toBeEnabled();
	await page.reload();
	await expect(
		page.locator('[data-role="assistant"]').filter({ hasText: '浏览器恢复验证' }),
	).toHaveCount(1);
});

test('an admission conflict clears the outgoing command and releases the composer', async ({
	page,
}) => {
	let posts = 0;
	await page.route('**/api/v1/ag-ui/runs', async (route) => {
		if (Object.keys(route.request().postDataJSON().forwardedProps ?? {}).length)
			return route.continue();
		posts++;
		await route.fulfill({
			status: 409,
			contentType: 'application/json',
			body: '{"code":"IDEMPOTENCY_KEY_REUSED"}',
		});
	});
	const input = page.getByPlaceholder('输入消息...');
	await input.fill('conflict');
	await input.press('Enter');
	await expect(page.getByRole('alert').filter({ hasText: 'IDEMPOTENCY_KEY_REUSED' })).toBeVisible();
	await expect(input).toBeEnabled();
	expect(
		await page.evaluate(
			(id) => sessionStorage.getItem('harness.chat.command.' + id),
			path!.split('/').at(-1),
		),
	).toBeNull();
	expect(posts).toBe(1);
});
