import { QueryClientProvider } from '@tanstack/react-query';
import { Onborda, OnbordaProvider } from 'onborda';
import { useMemo } from 'react';
import {
	createBrowserRouter,
	Navigate,
	RouterProvider,
	useLocation,
	useNavigate,
} from 'react-router-dom';
import { Toaster } from 'sonner';

import { MCPHubPage } from './pages/mcp';
import { SkillHubPage } from './pages/skill';
import { signedIn } from '@/api/harness';
import { RouteError } from '@/components/error/RouteError';
import { AppLayout } from '@/components/layout/AppLayout';
import { buildChatTour } from '@/components/tour/chatTourSteps';
import { TourCard } from '@/components/tour/TourCard';
import { UploadProvider } from '@/context/UploadContext';
import { useTranslation } from '@/i18n/useI18n';
import { queryClient } from '@/lib/query-client';
import { HttpChannels } from '@/pages/channel/HttpChannels';
import { ChatPage } from '@/pages/chat';
import { CredentialPage } from '@/pages/credential';
import { HarnessResources } from '@/pages/HarnessResources';
import { KnowledgePage } from '@/pages/knowledge';
import { SchedulePage } from '@/pages/schedule';
import { SetupPage } from '@/pages/setup';
import { VoiceProfilePage } from '@/pages/voice-profile';

function WorkspaceRoute() {
	const location = useLocation();
	return signedIn() ? (
		<AppLayout />
	) : (
		<Navigate to="/setup" replace state={{ from: location.pathname }} />
	);
}
function SetupPageRoute() {
	const location = useLocation();
	const navigate = useNavigate();
	return (
		<>
			<div className="h-screen">
				<SetupPage
					onComplete={() => {
						queryClient.clear();
						navigate(location.state?.from ?? '/chat', { replace: true });
					}}
				/>
			</div>
			<Toaster richColors position="top-right" />
		</>
	);
}

const router = createBrowserRouter([
	{
		element: <WorkspaceRoute />,
		errorElement: <RouteError />,
		children: [
			{
				// Content-level boundary: a crash in a page replaces only
				// the Outlet area, so AppLayout (the icon rail / nav) stays
				// usable. The parent route keeps its own errorElement as a
				// last-resort catch-all for AppLayout/AppSidebar crashes.
				errorElement: <RouteError />,
				children: [
					{ path: '/', element: <Navigate to="/chat" replace /> },
					{
						path: '/chat/:agentId?/:sessionId?/:memberId?',
						element: <ChatPage />,
					},
					{
						path: '/ag-ui/:threadId',
						lazy: async () => ({ Component: (await import('./pages/agui')).default }),
					},
					{ path: '/schedule', element: <SchedulePage /> },
					{ path: '/channel', element: <HttpChannels /> },
					{ path: '/credential', element: <CredentialPage /> },
					{ path: '/mcp', element: <MCPHubPage /> },
					{ path: '/mcp/:hubId', element: <MCPHubPage /> },
					{ path: '/skill', element: <SkillHubPage /> },
					{ path: '/skill/:hubId', element: <SkillHubPage /> },
					{ path: '/knowledge', element: <KnowledgePage /> },
					{ path: '/knowledge/:kbId', element: <KnowledgePage /> },
					{ path: '/voice-profile', element: <VoiceProfilePage /> },
					{
						path: '/workspace/skills',
						element: <HarnessResources key="skills" skills />,
					},
					{
						path: '/workspace/memory',
						element: <HarnessResources key="memory" skills={false} />,
					},
				],
			},
		],
	},
	{ path: '/setup', element: <SetupPageRoute />, errorElement: <RouteError /> },
]);

function App() {
	const { t } = useTranslation();
	const tours = useMemo(() => [buildChatTour(t)], [t]);

	return (
		<QueryClientProvider client={queryClient}>
			<OnbordaProvider>
				<Onborda
					steps={tours}
					cardComponent={TourCard}
					shadowOpacity="0.6"
					cardTransition={{ type: 'spring', duration: 0.4 }}
				>
					<UploadProvider>
						<RouterProvider router={router} />
					</UploadProvider>
					<Toaster richColors position="top-right" />
				</Onborda>
			</OnbordaProvider>
		</QueryClientProvider>
	);
}

export default App;
