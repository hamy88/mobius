import { createContext, useContext } from 'react'

export type DisplayFileContextValue = { projectId: string; sessionId: string; bindPath?: string }
export const DisplayFileContext = createContext<DisplayFileContextValue | null>(null)
export const useDisplayFileContext = () => useContext(DisplayFileContext)
