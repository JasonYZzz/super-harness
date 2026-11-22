import { defineConfig } from '@playwright/test';
export default defineConfig({
	testDir: './test',
	timeout: 60000,
	workers: 1,
	use: { locale: 'zh-CN', baseURL: process.env.SH_API_URL ?? 'http://127.0.0.1:8080' },
});
