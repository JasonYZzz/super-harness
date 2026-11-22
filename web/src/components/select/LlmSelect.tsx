import { useQuery } from '@tanstack/react-query';
import { ChevronDown, Settings2, Ban } from 'lucide-react';

import type { ChatModelConfig } from '@/api';
import { api, signedIn } from '@/api/harness';
import { Button } from '@/components/ui/button';
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuGroup,
	DropdownMenuItem,
	DropdownMenuLabel,
	DropdownMenuSeparator,
	DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useTranslation } from '@/i18n/useI18n.ts';
import { cn } from '@/lib/utils';

interface Props extends Omit<React.ComponentPropsWithoutRef<typeof Button>, 'onChange' | 'value'> {
	value?: ChatModelConfig | null;
	/**
	 * Called when the user selects a model, or — when `allowClear` is true —
	 * clears the selection (in which case `null` is emitted).
	 */
	onChange?: (value: ChatModelConfig | null) => void;
	onAddCredential?: () => void;
	/** Override the trigger label shown when no model is selected. */
	placeholder?: string;
	/**
	 * When true, append a "clear selection" item to the dropdown that emits
	 * `null` via `onChange`. Used by the fallback selector.
	 */
	allowClear?: boolean;
	selectionDisabled?: boolean;
	/** Override the label of the "clear selection" item. */
	clearLabel?: string;
}

export function LlmSelect({
	value,
	onChange,
	onAddCredential,
	placeholder,
	allowClear = false,
	selectionDisabled = false,
	clearLabel,
	className,
	...props
}: Props) {
	const { t } = useTranslation();
	const models = useQuery({
		queryKey: ['tenant-chat-models'],
		enabled: signedIn(),
		queryFn: () => api<{ models: string[] }>('/tenant'),
	});
	const groups = new Map<string, string[]>();
	for (const id of models.data?.models ?? []) {
		const provider = id.includes(':') ? id.split(':')[0] : 'Local';
		groups.set(provider, [...(groups.get(provider) ?? []), id]);
	}
	const displayLabel = value?.model || placeholder || t('llm-select.placeholder');
	return (
		<DropdownMenu>
			<DropdownMenuTrigger asChild>
				<Button
					variant="outline"
					size="sm"
					className={cn('justify-between gap-1 font-normal', className)}
					{...props}
				>
					<span className="truncate">{displayLabel}</span>
					<ChevronDown className="size-3.5 text-muted-foreground" />
				</Button>
			</DropdownMenuTrigger>
			<DropdownMenuContent align="start" className="min-w-48 max-h-72 overflow-y-auto">
				{models.error && <DropdownMenuLabel role="alert">{models.error.message}</DropdownMenuLabel>}
				{Array.from(groups, ([provider, ids]) => (
					<DropdownMenuGroup key={provider}>
						<DropdownMenuLabel>{provider}</DropdownMenuLabel>
						{ids.map((model) => (
							<DropdownMenuItem
								key={model}
								disabled={selectionDisabled}
								onSelect={() =>
									onChange?.({
										type: 'java',
										credential_id: '',
										model_class: '',
										model,
										parameters: {},
									})
								}
							>
								{model}
							</DropdownMenuItem>
						))}
					</DropdownMenuGroup>
				))}
				<DropdownMenuSeparator />
				{allowClear && (
					<DropdownMenuItem onSelect={() => onChange?.(null)} disabled={!value}>
						<Ban className="size-4" />
						<span>{clearLabel ?? t('llm-select.clear')}</span>
					</DropdownMenuItem>
				)}
				<DropdownMenuItem onSelect={onAddCredential}>
					<Settings2 className="size-4" />
					<span>{t('llm-select.serverConfig')}</span>
				</DropdownMenuItem>
			</DropdownMenuContent>
		</DropdownMenu>
	);
}
