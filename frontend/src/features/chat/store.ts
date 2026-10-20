import { useSyncExternalStore } from 'react'
import type { Message } from './types'

let messages: Message[] = []
const listeners = new Set<() => void>()

export function getChatMessages(): Message[] {
  return messages
}

export function setChatMessages(next: Message[]): void {
  messages = next
  for (const listener of listeners) listener()
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function useChatMessages(): [Message[], typeof setChatMessages] {
  return [useSyncExternalStore(subscribe, getChatMessages, getChatMessages), setChatMessages]
}
