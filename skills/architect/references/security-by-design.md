# Security by design (architect)

Status tags: V verified on a primary page, P partly verified or
experience-based, T judgement (verify in your environment). Sources:
`../SOURCES.md`.

This file covers architectural decisions only. The security rules themselves
belong to backend-security-privacy.

## 1. ARCH-12 Threat modelling (V for the four questions, T for the rest)
- Use the four questions:
  1. What are we working on?
  2. What can go wrong?
  3. What are we going to do about it?
  4. Did we do a good enough job?
- Mark the trust boundaries on the C4 container diagram (`../templates/c4.md`).
- STRIDE is a prompt list for "what can go wrong", not a requirement.
- Output: a short threat list per trust boundary, in the format below.
- Ownership: this skill owns the system-level threat model (one list per
  trust boundary, at design time). The per-feature STRIDE pass before a
  High-risk feature is owned by Rules:
  `backend-security-privacy/references/authorization-and-abuse.md`
  (section "Threat modelling").

Threat list format (one table per trust boundary):

| Threat | Affected component | Owner | Mitigation (rule pointer) |
|---|---|---|---|
| Example: a caller reads another tenant's records | API | Backend owner | Rules: `backend-security-privacy/references/authorization-and-abuse.md` |

## 2. ARCH-13 Secure by default (V after rewording)
From the CISA Secure by Design principles (April 2023 joint guide and the
October 2023 alert):
- Security features are on by default and cost nothing extra.
- Turning one off is an explicit, logged choice.
- Privileged accounts get MFA by default (mechanics: Rules:
  `backend-security-privacy/references/auth-and-sessions.md`, section "MFA").
- Remove features that only enlarge the attack surface.
- The maker owns the customer's security outcome.

## 3. ARCH-14 Authorization enforcement point (T)
- Decide where the central authorization enforcement point sits in each
  module, deny by default.
- Rules: `backend-security-privacy/references/authorization-and-abuse.md`.

## 4. ARCH-15 Data classes (T)
- Map data classes (public, internal, personal, sensitive) to components
  before choosing storage and hosting.
- Sensitive data crosses the fewest boundaries.
- Class handling rules: Rules:
  `backend-security-privacy/references/data-protection-logging.md`.
