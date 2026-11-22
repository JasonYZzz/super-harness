import { CircleAlert, Loader2 } from 'lucide-react';
import { useEffect, useState } from 'react';

import { http } from '@/api/harness';
import { Alert, AlertDescription } from '@/components/ui/alert.tsx';
import { Button } from '@/components/ui/button.tsx';
import {
	Card,
	CardContent,
	CardDescription,
	CardHeader,
	CardTitle,
} from '@/components/ui/card.tsx';
import { Field, FieldDescription, FieldGroup, FieldLabel } from '@/components/ui/field.tsx';
import { Input } from '@/components/ui/input.tsx';
import { useTranslation } from '@/i18n/useI18n.ts';
import { formatApiErrorForAlert } from '@/lib/api-error.ts';
import { cn } from '@/lib/utils.ts';

interface Props {
	onComplete: () => void;
	className?: string;
}

export const SetupPage = ({ onComplete, className }: Props) => {
	const { t } = useTranslation();
	const url = window.location.origin;
	const [username, setUsername] = useState('');
	const [password, setPassword] = useState('');
	const [accessToken, setAccessToken] = useState('');
	const [localLogin, setLocalLogin] = useState(false);
	const [tokenMode, setTokenMode] = useState(false);
	const usePassword = localLogin && !tokenMode;
	useEffect(() => {
		const controller = new AbortController();
		fetch('/api/v1/auth/mode', { signal: controller.signal })
			.then(r => r.ok ? r.json() : null)
			.then(mode => setLocalLogin(mode?.localLogin === true))
			.catch(() => {});
		return () => controller.abort();
	}, []);
	const [checking, setChecking] = useState(false);
	const [errorMsg, setErrorMsg] = useState('');

	async function handleSubmit(e: React.FormEvent) {
		e.preventDefault();
		setChecking(true);
		setErrorMsg('');
		try {
			let token = accessToken.trim();
			if (usePassword) {
				const response = await fetch('/api/v1/auth/login', {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({ username, password }),
				});
				if (!response.ok) throw new Error(response.status === 401 ? '用户名或密码错误' : '登录失败 (' + response.status + ')');
				token = (await response.json()).accessToken;
			}
			await http('/tenant', { headers: { Authorization: 'Bearer ' + token } });
			sessionStorage.setItem('harness.access', token);
			localStorage.removeItem('chat_last_agent');
			localStorage.removeItem('chat_last_session');
			onComplete();
		} catch (err) {
			setErrorMsg(formatApiErrorForAlert(err));
		} finally {
			setChecking(false);
		}
	}

	return (
		<div className="flex items-center justify-center h-full">
			<div className={cn('flex flex-col gap-6 w-full max-w-sm', className)}>
				<Card>
					<CardHeader>
						<CardTitle>{t('setup.title')}</CardTitle>
						<CardDescription>连接当前 Java 服务</CardDescription>
					</CardHeader>
					<CardContent>
						<form onSubmit={handleSubmit}>
							<FieldGroup>
								<Field>
									<FieldLabel htmlFor="server-url-input">{t('setup.serverUrl')}</FieldLabel>
									<Input
										id="server-url-input"
										type="url"
										placeholder={t('setup.serverUrlPlaceholder')}
										value={url}
										readOnly
										required
									/>
								</Field>
								<Field>
									<FieldLabel htmlFor="username-input">{usePassword ? '用户名' : '访问令牌'}</FieldLabel>
									<Input
										id="username-input"
										type={usePassword ? 'text' : 'password'}
										autoComplete={usePassword ? 'username' : 'off'}
										placeholder={usePassword ? "admin" : "Bearer token"}
										value={usePassword ? username : accessToken}
										onChange={(e) => (usePassword ? setUsername : setAccessToken)(e.target.value)}
										required
									/>
								</Field>
								{usePassword && <Field>
									<FieldLabel htmlFor="local-password">密码</FieldLabel>
									<Input id="local-password" type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required />
								</Field>}
								{localLogin && <Button variant="outline" type="button" onClick={() => { setTokenMode(!tokenMode); setErrorMsg(''); }}>
									{usePassword ? '使用访问令牌' : '使用本地账号'}
								</Button>}
								{errorMsg && (
									<Alert variant="destructive">
										<CircleAlert />
										{/* The failing-component list is appended on its
										    own line, so newlines have to survive. */}
										<AlertDescription className="whitespace-pre-wrap">{errorMsg}</AlertDescription>
									</Alert>
								)}
								<Field>
									<Button type="submit" className="w-full" disabled={checking}>
										{checking && <Loader2 className="size-3.5 animate-spin" />}
										{checking ? t('setup.checking') : t('setup.submit')}
									</Button>
								</Field>
							</FieldGroup>
						</form>
						{sessionStorage.getItem('harness.access') && <Button variant="ghost" className="mt-2 w-full" onClick={() => {
							sessionStorage.removeItem('harness.access');
							window.location.replace('/setup');
						}}>退出登录</Button>}
					</CardContent>
				</Card>
				<FieldDescription className="px-6 text-center">
					{usePassword ? '本地调试默认用户名和密码均为 admin。' : '请输入当前服务的访问令牌。'}
				</FieldDescription>
			</div>
		</div>
	);
};
