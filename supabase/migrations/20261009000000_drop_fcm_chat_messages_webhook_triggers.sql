drop trigger if exists trg_chat_messages_fcm_edge_webhook on public.chat_messages;
drop trigger if exists tr_forward_chat_row_change_to_firebase on public.chat_messages;
drop trigger if exists tr_forward_chat_row_change_to_fcm_edge on public.chat_messages;

drop function if exists public.forward_chat_row_change_to_fcm_edge() cascade;
drop function if exists public.forward_chat_row_change_to_firebase() cascade;
