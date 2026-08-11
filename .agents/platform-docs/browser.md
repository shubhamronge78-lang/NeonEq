# browser

> Browser automation — navigating web pages, taking screenshots, interacting with elements

A browser session is automatically created when you first use any of the browser tools in your tool list (the tools for navigating, reading page content, taking screenshots, clicking, typing, and stopping the session). You do NOT need to create a session manually. Always call these tools by their exact names as they appear in your tool list — do not invent shortened or generic tool names.

- When a session starts, the user automatically sees a live browser view inline in the chat.
- Cookies and login sessions are automatically persisted. If you log in to a site in one session, you will still be logged in in future sessions.
- Do NOT install Playwright, Puppeteer, websockets, or ANY browser automation library. Do NOT pip install anything for browser control. You already have built-in tools.
- Typical workflow: navigate to the page -> read its content or take a screenshot -> interact as needed -> stop the session when finished.
- Use screenshots to inspect the page visually, and the content-reading tool to extract text.
