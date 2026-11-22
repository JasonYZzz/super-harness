import { api, agents, saveAgent, signedIn } from './harness';
import type { CreateAgentRequest, UpdateAgentRequest, AgentSchemaV2Response } from './types';
export const agentApi = {
	list: agents,
	getSchema: async (): Promise<AgentSchemaV2Response> => {
		const { models, defaultModel } = signedIn()
			? await api<{ models: string[]; defaultModel?: string }>('/tenant')
			: { models: [], defaultModel: undefined };
		return {
			schema: {
				type: 'object',
				required: ['name', 'model'],
				properties: {
					name: { type: 'string', title: '名称' },
					system_prompt: { type: 'string', title: '系统指令' },
					model: { type: 'string', title: '模型', enum: models, default: defaultModel ?? models[0] },
					publish_artifact: { type: 'boolean', title: '审批后发布文本产物', default: false },
					use_memory: { type: 'boolean', title: '使用当前用户保存的记忆', default: false },
					sandbox: { type: 'boolean', title: '启用沙箱', default: false },
				},
			},
		};
	},
	create: async (body: CreateAgentRequest) => ({
		agent_id: (await saveAgent(body)).agentId,
	}),
	update: async (id: string, body: UpdateAgentRequest) => {
		await saveAgent(body, id);
		return (await agents()).agents.find((a) => a.id === id)!;
	},
	delete: (id: string) => api<void>('/agents/' + id, 'DELETE'),
};
