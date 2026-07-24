-- CHAT-32: room_members' PK is (room_id, user_id), so a user_id = ? predicate
-- can't use the PK btree (user_id is the trailing column). GET /rooms hits
-- this on every call via RoomMemberRepository.findByUserId and the roomId
-- projection subquery in RoomRepository.findVisibleToUser, sequential-scanning
-- room_members each time. Index on (user_id, room_id) so both queries can use
-- the btree, with the room_id projection served index-only.
CREATE INDEX idx_room_members_user_id ON room_members (user_id, room_id);
