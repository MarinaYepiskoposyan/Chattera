# Functional Requirements — Chattera

## Project Goal
Create a scalable chat application that supports:
- public or private chat rooms
- one-to-one conversations
- file exchange between users

## Functional Requirements

### FR-01: User account and authentication
- Users must be able to register, log in, and log out.
- Users must be able to view and update their profile.
- Authentication must support secure access for web and mobile clients.

### FR-02: Chat rooms
- Users must be able to create, join, leave, and send messages in chat rooms.
- Chat rooms must support multiple participants.
- Room participants must see message history.

### FR-03: One-to-one chat
- Users must be able to start private conversations with another user.
- Private conversations must maintain message history.
- Users must receive real-time updates for incoming private messages.

### FR-04: File sharing
- Users must be able to upload files and share them with others.
- Shared files must be accessible through chat history.
- The system must store file metadata and provide download access.

### FR-05: Real-time messaging
- Messages must be delivered in real time.
- Users must see message status such as sent, delivered, and read.
- The system must support concurrent users.

### FR-06: Notifications
- Users must receive notifications for new messages and file shares.
- Notifications must be available in-app and can be extended to push/email later.

## Non-Functional Requirements
- Support up to 1,000,000 end users.
- Maintain low-latency message delivery for active conversations.
- Provide high availability and resilience for core chat services.
- Protect user data with secure storage and access control.

## Scope for Sprint 1
Sprint 1 will focus on the MVP foundation:
- authentication and profile basics
- chat rooms
- one-to-one messaging
- file upload/download foundation
- deployment and observability baseline

## Out of Scope for Sprint 1
- full-scale global deployment design
- advanced moderation and admin tooling
- enterprise compliance workflows
- the detailed scalability architecture discussion, which will be handled separately
