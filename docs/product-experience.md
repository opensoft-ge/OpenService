# OpenService product experience

Status reviewed 2026-11-15: Implemented for the existing browser interface; acceptance and exact coverage gates passed. Theme preference is device-local; account synchronization and administrator-defined theme publishing are not implemented.

## Required product experience

- Use a restrained professional palette, clear typography, consistent spacing, predictable navigation and coherent controls. Default direction: blue-grey surfaces, steel-blue actions, slate text and semantic status colours. Preserve product-specific workflows; do not copy another product's proprietary visual assets.
- Provide complete light and dark themes using semantic colour tokens. Cover public login/invitation/recovery pages as well as authenticated navigation, lists, forms, dialogs, reports, charts, empty/error/loading states and admin screens when present. Do not simulate dark mode with an inversion filter.
- Put a clearly labelled, keyboard-operable theme toggle in a consistent, easily found position, including on mobile. Expose its state to assistive technology. Use the system preference initially; persist an explicit choice across navigation and reloads without a server round trip. Handle unavailable browser storage gracefully, preserve the current-page choice and keep the app usable without JavaScript. State whether preferences are device-local or account-synchronized.
- Meet WCAG AA contrast targets in both themes: at least 4.5:1 for normal text, 3:1 for large text and meaningful control/focus indicators. Do not convey status solely by colour. Respect reduced motion; keep focus visible, controls labelled and content usable on narrow screens and at zoom.
- Qualify real workflows in both themes with browser assertions, keyboard checks, automated accessibility scans and visual inspection at desktop/mobile sizes. Check first load, OS preference changes, manual override, navigation, reload and storage failures. Apply existing exact executable-code coverage gates to theme behaviour; screenshots and passing contrast scans alone do not establish full accessibility conformance.

Sources: [W3C text contrast](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html), [non-text contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html) and [visible focus](https://www.w3.org/WAI/WCAG22/Understanding/focus-visible.html), reviewed 2026-11-15. The palette and interaction requirements are OpenSoft product decisions, not vendor parity claims.

## Delivery tracking

Experience ID: `UX-001`. Track implementation and remaining work in the [capability ledger](servicenow-compatibility.md). For a first UI release, connect real authenticated product workflows, implement both themes and qualify the above journeys. A design requirement does not change the implemented API/CLI contract.

## OpenService implementation — UX-001 / OS-051

The signed-out toolbar and signed-in header expose **Dark mode**, a native toggle button with an announced pressed state and a visible switch. The app starts with the operating-system preference. An explicit choice is saved as `openservice.theme` in browser-local storage for this origin; it applies across navigation, login/logout and reloads. Clearing that preference restores system selection. It is not synchronized between devices or already-open tabs. If storage is blocked, the toggle works for the current page. Without JavaScript, the readable light theme and ordinary server-rendered forms remain available and the inactive toggle stays hidden.

The self-hosted `theme.js` runs before the stylesheet to set the first-paint theme. No inline-script CSP exemption, external theme service, tracking, image inversion or copied artwork is used. Shared semantic tokens in `app.css` cover content surfaces, inputs, navigation, tables, badges, notices and focus states. The palette uses steel blue, grey-blue and slate with teal success and amber warning colours; status text remains present.

`ThemeBrowserAcceptance` exercises system changes, both stored choices, invalid preferences, keyboard Enter/Space activation, navigation/reload/logout, storage denial, no-script fallback and signed-out asset access. It checks overview, record lists/forms, dictionary, account/invitation/delivery administration, migration and account security in both themes, including narrow-screen use. `ReleaseBrowserAcceptance` also checks both themes throughout the real invitation → requester work → recovery → deactivation journey. Screenshots are generated under `target/browser-artifacts/`; coverage results and scope are in [validation](validation.md).

## Compatibility and next gaps

[ServiceNow Working with themes](https://www.servicenow.com/docs/r/platform-user-interface/next-experience-theming.html), retrieved 2026-11-15, resolved to **Brazil**, updated 2026-09-10. It documents theme variants and administrator theme creation/publishing; the source notes that custom components do not automatically adopt theming. This is supplemental discovery, not a change to the provisional Australia API contract or a licensed-instance conformance claim. OpenService's local two-theme preference is an original bounded capability; it does not implement ServiceNow Theme Builder or its configuration tables.

`UX-002` remains planned: administrator-defined themes, safe preview/publishing, scoped availability and account-synchronized preferences. Dependencies: versioned configuration and the relevant administration permissions. Acceptance must cover role restrictions, invalid/low-contrast palettes, preview without publication, rollback, existing user overrides and upgrades. Current scope remains the maintained professional light/dark pair, not arbitrary user CSS.

## AI product direction

Requirement added 2026-11-16: OpenService must become fully AI enabled, agentic and conversational across its core journeys. The [AI capability and acceptance guide](ai-capabilities.md) defines the required work and evidence. This direction does not change the implementation scope or historical validation results recorded above.
