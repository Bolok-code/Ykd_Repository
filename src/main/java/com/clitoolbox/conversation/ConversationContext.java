package com.clitoolbox.conversation;

import java.time.LocalDate;

public record ConversationContext(String intent,
                                  String city,
                                  LocalDate targetDate){
}
