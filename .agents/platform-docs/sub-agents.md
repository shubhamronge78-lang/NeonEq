# sub-agents

> Built-in sub-agent delegation tools

Sub-agents are built-in helper agents that the parent Superagent can delegate focused work to when `sub_agent` is present in the current tool list. Use them when the work is noisy, parallelizable, or benefits from a narrow worker brief. Do quick single-step work inline.

Use `sub_agent` when delegating. It launches immediately in the background — there is no user approval step — and does not block chat. Before calling it, tell the user in one short sentence that you're handing the work to background sub-agents: they're great for complicated tasks but can use credits faster, and the user can stop them anytime or ask you not to use sub-agents. Then call `sub_agent` right away; do not write a long plan or list every subtask first. If the user has asked you not to use sub-agents, respect that and work inline. For parallel work, make one `sub_agent` call with `tasks: [{topic, prompt}, ...]` to launch the whole mission at once; keep the conversation to at most 12 active sub-agent tasks. Do not poll `sub_agent_status` while a group is running; use it for explicit user status requests or one-off inspection. Use `sub_agent_result` with task_id, offset, and limit when a result preview is not enough. Use `sub_agent_stop` to stop a group and get results collected so far. Do not invent generic tool names such as `spawn_sub_agent`, `create_agent`, or `delegate_to_agent`. If no `sub_agent` tool is present in your tool list, then delegation is unavailable in this conversation.

Make the handoff self-contained: task, inputs/scope, and "return only" format. For exact computation, verification, or data processing, tell the child to use bash/Python and prefer an exact command. Specify indexing semantics explicitly, for example "0-based Python slice rows[0:137]" or "1-based inclusive file lines 1-137." Do not delegate vague ranges like "lines 0-136" without saying whether they are line numbers or array indices.

For web research tasks, tell each child to start with 1-2 high-signal searches or sources unless the user explicitly requested exhaustive research.

Each sub-agent run has its own child conversation and returns a compact result to the parent. Synthesize results yourself; do not paste raw worker output unless the requested artifact is the output.