// lib/widgets/chat_bubble.dart
//
// Sprint 22 — WhatsApp-style chat bubble (right-aligned).
//
// Used to preview the message that will be sent via the deep link
// on the WhatsApp task detail screen (Sprint 22.8). Color is the
// canonical WhatsApp outgoing-bubble (#DCF8C6) with an asymmetric
// `BorderRadius` (16/16/16/4) for the tail.

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

class ChatBubble extends StatelessWidget {
  const ChatBubble({
    super.key,
    required this.text,
    required this.timestamp,
  });

  final String text;
  final String timestamp;

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.centerRight,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 280),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: const BoxDecoration(
            color: AppTheme.whatsappBubble,
            borderRadius: BorderRadius.only(
              topLeft: Radius.circular(16),
              topRight: Radius.circular(16),
              bottomLeft: Radius.circular(16),
              bottomRight: Radius.circular(4),
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                text,
                style: const TextStyle(
                  fontSize: 14,
                  height: 1.5,
                  color: AppTheme.text,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                timestamp,
                style: const TextStyle(
                  fontSize: 10,
                  color: AppTheme.muted,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
