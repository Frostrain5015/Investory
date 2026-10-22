import { CircleAlert, CircleStop } from 'lucide-react'
import type { Message } from './types'

/** A turn status, deliberately outside the assistant's answer bubble. */
export default function ConversationNotice({ message }: { message: Message }) {
  const stopped = message.notice?.kind === 'stopped'
  const Icon = stopped ? CircleStop : CircleAlert
  return (
    <div role="status" className="flex max-w-[85%] items-start gap-2 px-1 py-1 text-xs leading-5 text-amber-700 dark:text-amber-400 motion-safe:animate-[notice-in_180ms_ease-out]">
      <Icon aria-hidden="true" size={14} strokeWidth={1.7} className="mt-[3px] shrink-0" />
      <span className="whitespace-pre-wrap break-words">{message.content.replace(/^⚠\s*/, '')}</span>
    </div>
  )
}
