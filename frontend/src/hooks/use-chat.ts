import { createContext, useContext } from 'react'

export interface ChatContextType {
  openChatWith: (message: string) => void
}

export const ChatContext = createContext<ChatContextType>({ openChatWith: () => {} })

export function useChatContext() {
  return useContext(ChatContext)
}
