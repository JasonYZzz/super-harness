import { Link, Outlet, useLocation } from 'react-router-dom';


import { AppSidebar } from '@/components/layout/AppSidebar';
import { SidebarInset, SidebarProvider } from '@/components/ui/sidebar';
import { useTranslation } from '@/i18n/useI18n';

const pages: Record<
	string,
	{ message: string; link?: string; label?: string }
> = {
	schedule: { message: 'harness.schedule' },
	channel: { message: 'harness.channel' },
	credential: { message: 'harness.credential' },
	'voice-profile': { message: 'harness.voice' },
	knowledge: {
		message: 'harness.knowledge',
		link: '/workspace/memory',
		label: 'harness.memory',
	},
	skill: { message: 'harness.skill', link: '/workspace/skills', label: 'harness.manageSkills' },
	mcp: { message: 'harness.mcp' },
};
export function AppLayout() {
	const page = pages[useLocation().pathname.split('/')[1]];
	const { t } = useTranslation();
	return (
		<div className="h-screen flex">
			<SidebarProvider>
				<AppSidebar />
				<SidebarInset className="flex-1 overflow-hidden bg-canvas">
					{page && (
						<aside
							role="note"
							className="flex shrink-0 flex-wrap items-center gap-3 px-4 py-2 text-xs text-muted-foreground"
						>
							{t(page.message)}{' '}
							{page.link && (
								<Link className="text-foreground underline" to={page.link}>
									{t(page.label!)}
								</Link>
							)}
						</aside>
					)}
					<Outlet />
				</SidebarInset>
			</SidebarProvider>
		</div>
	);
}
