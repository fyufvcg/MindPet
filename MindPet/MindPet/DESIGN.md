---
name: MindPet
description: 温和亲近的桌面 AI 陪伴与本地工作助手
colors:
  primary-blue: "#3976f6"
  primary-blue-dark: "#74aaf4"
  dark-app: "#0b1220"
  dark-sidebar: "#101a2b"
  dark-content: "#0e1726"
  dark-card: "#121d2f"
  light-app: "#f1f5f9"
  light-content: "#f8fafc"
  light-card: "#ffffff"
  text-dark-primary: "#f8fafc"
  text-light-primary: "#000000"
  text-muted: "#64748b"
  success: "#10b981"
  danger: "#ef4444"
  warning: "#f97316"
typography:
  body:
    fontFamily: "'Segoe UI Variable', 'PingFang SC', 'Microsoft YaHei', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.5
  companion:
    fontFamily: "Outfit, 'Segoe UI Variable', sans-serif"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: 1.5
  label:
    fontFamily: "'Segoe UI Variable', 'PingFang SC', 'Microsoft YaHei', sans-serif"
    fontSize: "12px"
    fontWeight: 600
    lineHeight: 1.35
  technical:
    fontFamily: "'SFMono-Regular', 'SF Mono', 'Cascadia Mono', Consolas, monospace"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.5
  scale:
    xs: "10px"
    sm: "11px"
    md: "12px"
    body: "13px"
    lg: "14px"
    lg-plus: "15px"
    xl: "16px"
    xl-plus: "18px"
    2xl-plus: "21px"
    2xl-wide: "22px"
    2xl: "18px"
    3xl: "20px"
    4xl: "24px"
    4xl-plus: "26px"
    5xl: "27px"
    5xl-plus: "28px"
    6xl: "34px"
    7xl: "36px"
    8xl: "40px"
    display: "32px"
    hero: "56px"
rounded:
  hairline: "1px"
  2xs: "2px"
  xs: "4px"
  sm-tight: "5px"
  sm: "6px"
  sm-plus: "7px"
  md: "8px"
  md-plus: "9px"
  lg: "10px"
  lg-plus: "11px"
  xl: "12px"
  2xl: "14px"
  3xl: "16px"
  4xl: "18px"
  5xl: "20px"
  6xl: "22px"
  7xl: "28px"
  pill: "100px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "20px"
components:
  button-primary:
    backgroundColor: "{colors.primary-blue}"
    textColor: "#ffffff"
    rounded: "{rounded.lg}"
    padding: "10px 12px"
  button-secondary:
    backgroundColor: "{colors.dark-card}"
    textColor: "{colors.primary-blue-dark}"
    rounded: "{rounded.lg}"
    padding: "10px 12px"
  input:
    backgroundColor: "{colors.light-card}"
    textColor: "{colors.text-light-primary}"
    rounded: "{rounded.sm}"
    padding: "6px 10px"
  card:
    backgroundColor: "{colors.light-card}"
    textColor: "{colors.text-light-primary}"
    rounded: "{rounded.md}"
    padding: "16px"
---

# Design System: MindPet

## Overview

**Creative North Star: "The Gentle Desktop Companion"**

MindPet's current interface combines the calm familiarity of a desktop utility with the warmth of a companion that is always nearby. The visual language is quiet, direct, and task-ready: conversation, memory, local actions, and MCP capabilities should feel like parts of one dependable workspace rather than separate product modes.

The system uses restrained blue emphasis, translucent state layers, compact controls, and a dark/light theme pair. Rounded surfaces and small status signals add approachability without turning the product into a toy. The user has confirmed that the system may evolve, but it should avoid marketing-page composition and decorative excess.

**Key Characteristics:**
- Warm utility rather than promotional spectacle.
- Compact, scannable desktop density.
- Blue interaction emphasis with semantic green, orange, and red states.
- Companion personality expressed through behavior and small details, not ornamental decoration.

## Colors

The palette is a cool neutral foundation with a clear blue interaction voice. Dark mode is deep navy rather than pure black; light mode is cool paper rather than stark white. Keep saturated colors for actions, status, and focus.

### Primary
- **Clear Interface Blue** (`#3976f6`): Primary actions, active navigation, focus, links, and key interactive affordances in light mode.
- **Soft Night Blue** (`#74aaf4`): The corresponding readable accent in dark mode.

### Neutral
- **Night App Navy** (`#0b1220`): Dark application background.
- **Night Sidebar** (`#101a2b`): Dark navigation and utility rail.
- **Night Content Navy** (`#0e1726`): Dark main content surface.
- **Night Card Navy** (`#121d2f`): Dark elevated container.
- **Cool Mist** (`#f1f5f9`): Light application background.
- **Cool Paper** (`#f8fafc`): Light content surface.
- **White Surface** (`#ffffff`): Light cards, inputs, and active titlebar tabs.
- **Muted Slate** (`#64748b`): Secondary labels, metadata, and inactive controls.

### Named Rules
**The Signal, Not Decoration Rule.** Blue, green, orange, and red accents should communicate interaction or status; do not add them merely to make a surface busier.

## Typography

**Display Font:** Inter, with system sans-serif fallbacks.
**Body Font:** Inter, with `-apple-system`, `BlinkMacSystemFont`, and `Segoe UI` fallbacks.
**Label/Mono Font:** System monospace for code and technical payloads only.

**Character:** Compact, neutral, and readable. Inter supports dense chat and tool surfaces while weight and color provide hierarchy instead of oversized display type.

### Hierarchy
- **Title** (700, 14px, compact line-height): Brand names, section titles, and compact window labels.
- **Body** (400, 13px, 1.5): Chat messages, tool descriptions, and ordinary settings content.
- **Label** (600-700, 10.5-12px, 1.35): Metadata, navigation groups, status labels, and compact controls.
- **Technical** (400, system monospace): Code, file paths, structured output, and developer-facing details.

### Named Rules
**The Conversation First Rule.** Keep ordinary text compact and comfortable for scanning; reserve heavier weights for state, action, and navigation hierarchy.

## Layout

The primary workspace uses a full-viewport flex layout with a collapsible left rail and a flexible conversation/content region. The default sidebar is approximately 210px wide and collapses to approximately 68px. Sidebar padding is compact and regular, with 4-20px spacing steps used to separate controls, recent sessions, and utility sections.

The interface is optimized for desktop operation: fixed titlebar controls, persistent navigation, scrollable recent sessions, and a main content area that absorbs remaining width. Cards and utility panels should remain dense enough to support repeated work. When the viewport narrows, preserve the conversation and primary action before secondary navigation.

## Elevation & Depth

Depth is a hybrid of tonal layering, low-contrast borders, and restrained shadows. Dark mode uses navy layers and translucent white borders; light mode uses cool gray borders and white surfaces. Shadows are most useful for floating menus, dialogs, pet bubbles, and transient controls rather than every card.

### Shadow Vocabulary
- **Soft control lift** (`0 2px 8px rgba(0, 0, 0, 0.05)`): Small buttons and compact controls in light mode.
- **Floating panel** (`0 12px 32px rgba(0, 0, 0, 0.16)`): Menus, dialogs, and elevated utility panels.
- **Companion bubble** (`0 10px 30px rgba(0, 0, 0, 0.45)`): Dark translucent desktop pet speech bubbles.

### Named Rules
**The Tonal Layer Rule.** Establish hierarchy with surface contrast and a quiet border before reaching for a large shadow.

## Shapes

The form language is gently rounded but operational. Inputs and compact controls favor 4-10px radii; cards and dialogs use 8-12px; circular shapes are reserved for avatars, status dots, collapse controls, and icon actions. Borders are usually thin and low contrast, with blue reserved for active or focused states.

Avoid decorative pills for ordinary controls. The 100px pill shape belongs to tags, status chips, or genuinely compact toggle-like items.

## Components

### Buttons
- **Shape:** Gently rounded, usually 6-10px; icon-only controls may be circular.
- **Primary:** Blue emphasis with white text when the action is central; use compact 10px vertical padding.
- **Secondary / Ghost:** Transparent or tonal surface with a low-contrast border and muted text; blue appears on hover or active state.
- **Hover / Focus:** Prefer a small tonal shift or 1px lift. Focus must remain visible through a blue border or outline.

### Cards / Containers
- **Corner Style:** 8px is the default; 10-12px for dialogs and richer companion panels.
- **Background:** White/cool paper in light mode; layered navy surfaces in dark mode.
- **Shadow Strategy:** Flat or lightly bordered at rest; stronger shadows only for floating or transient surfaces.
- **Border:** One-pixel low-contrast border, with blue only for active or selected states.
- **Internal Padding:** 12-16px for ordinary cards, with 20px reserved for larger panels.

### Inputs / Fields
- **Style:** Compact 6px radius, readable text, transparent or white tonal fill, and a low-contrast border.
- **Focus:** Blue border or outline without a large glow.
- **Disabled / Error:** Reduce contrast for disabled states; use semantic red for errors and preserve explanatory text.

### Navigation
- **Style:** Persistent left rail with brand/status block, new-chat action, recent sessions, and grouped utilities.
- **States:** Muted text at rest, subtle surface tint on hover, and blue text/border tint for active items.
- **Collapsed Treatment:** Preserve recognizable icons and status signals; hide secondary labels without changing the action order.

### Desktop Pet
The desktop pet is the signature component. Keep the pet visually present and approachable, with small circular controls and short translucent speech bubbles. Personality should come from timing, expression, memory, and useful responses; the bubble should remain a readable tool surface rather than a decorative ornament.

### Chat Messages
Chat content should favor clear role separation, comfortable line-height, and lightweight metadata. Agent output can use a slightly distinct tonal surface, but message chrome should not overpower the content or MCP results.

## Do's and Don'ts

### Do:
- **Do** use the blue accent to clarify action, selection, focus, and active state.
- **Do** maintain the dark/light theme relationship through equivalent semantic roles.
- **Do** keep navigation, chat, and tool output compact enough for repeated desktop use.
- **Do** let the desktop pet add warmth through interaction and small status details.
- **Do** use borders and tonal layers before adding stronger shadows.

### Don't:
- **Don't** use marketing-style hero layouts, promotional gradients, or oversized claims inside the product UI.
- **Don't** add decoration that competes with the conversation, task result, or next action.
- **Don't** turn every control into a pill or every panel into a floating card.
- **Don't** use saturated colors without a semantic interaction or status role.
- **Don't** make the pet's personality depend on visual noise or childish ornament.
