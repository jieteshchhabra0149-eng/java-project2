package com.chatapp.repository;

import com.chatapp.entity.StoredMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StoredMessageRepository extends JpaRepository<StoredMessage, Long> {
    List<StoredMessage> findTop100ByRoomIdOrderByCreatedAtAsc(String roomId);
    Optional<StoredMessage> findByMessageId(String messageId);
}
