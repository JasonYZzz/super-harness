import { execFileSync } from 'node:child_process';

import { test, expect } from '@playwright/test';

test('AG-UI browser replays interrupted publication after refresh', async ({ page }) => {
	const thread = process.env.SH_TEST_AGUI_THREAD;
	test.skip(!thread, 'Requires an isolated publish_text conversation.');
	await page.addInitScript(
		(token) => sessionStorage.setItem('harness.access', token),
		execFileSync('../scripts/dev-token.sh', { encoding: 'utf8' }).trim(),
	);
	await page.goto('/ag-ui/' + thread);
	await page.getByRole('textbox', { name: '消息' }).fill('浏览器恢复验证');
	await page.getByRole('button', { name: '发送', exact: true }).click();
	await expect(page.getByRole('button', { name: '批准发布' })).toBeVisible();
	await page.reload();
	await page.getByRole('button', { name: '批准发布' }).click();
	await expect(page.getByRole('status').filter({ hasText: '已完成' })).toBeVisible();
	await expect(page.getByText('收到：浏览器恢复验证', { exact: true })).toHaveCount(1);
	await page.reload();
	await expect(page.getByText('收到：浏览器恢复验证', { exact: true })).toHaveCount(1);
	const download = page.waitForEvent('download');
	await page.getByRole('button', { name: '下载 response.txt' }).click();
	const file = await download;
	expect(file.suggestedFilename()).toBe('response.txt');
	const stream = await file.createReadStream();
	const chunks = [];
	for await (const chunk of stream!) chunks.push(chunk);
	expect(Buffer.concat(chunks).toString('utf8')).toContain('浏览器恢复验证');
});
