package com.kssasarma.confluencebot.repository;

import com.kssasarma.confluencebot.domain.ConfluencePageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConfluencePageRepository extends JpaRepository<ConfluencePageEntity, String> {

    List<ConfluencePageEntity> findBySpaceKey(String spaceKey);

    @Query("SELECT e.version FROM ConfluencePageEntity e WHERE e.pageId = :pageId")
    Integer findVersionByPageId(@Param("pageId") String pageId);
}
