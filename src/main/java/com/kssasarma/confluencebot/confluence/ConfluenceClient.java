package com.kssasarma.confluencebot.confluence;

import com.kssasarma.confluencebot.confluence.dto.ConfluencePageDetail;
import com.kssasarma.confluencebot.confluence.dto.SpaceMetadata;
import java.util.List;

public interface ConfluenceClient {
    List<ConfluencePageDetail> fetchAllPages(String spaceKey);
    ConfluencePageDetail fetchPage(String pageId);
    SpaceMetadata fetchSpaceMetadata(String spaceKey);
}
