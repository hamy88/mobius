/**
 * agents/events.ts — neutral event bus for agent backends.
 *
 * Part of the agent infrastructure layer: it publishes raw agent events and deliberately
 * knows nothing about Mobius concepts such as sessions_v2, admin settings, titles or
 * repositories.
 */
const EventEmitter = require('events')

const agentEvents = new EventEmitter()
agentEvents.setMaxListeners(0)

// Publish a raw agent event to every subscriber.
function emitAgentRawEntry(payload: any) {
  agentEvents.emit('raw_entry', payload)
}

// Subscribe to raw agent events; returns the unsubscribe function.
function onAgentRawEntry(listener: (payload: any) => void) {
  agentEvents.on('raw_entry', listener)
  return () => agentEvents.off('raw_entry', listener)
}

module.exports = {
  emitAgentRawEntry,
  onAgentRawEntry,
}

// marker: make this file a module (top-level declarations file-private) for tsc
export {}
