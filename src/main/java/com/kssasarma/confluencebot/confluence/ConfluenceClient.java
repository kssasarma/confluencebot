package com.kssasarma.confluencebot.confluence;

import com.kssasarma.confluencebot.confluence.dto.ConfluencePageDetail;
import java.util.List;

public interface ConfluenceClient {
    List<ConfluencePageDetail> fetchAllPages(String spaceKey);
    ConfluencePageDetail fetchPage(String pageId);
}
