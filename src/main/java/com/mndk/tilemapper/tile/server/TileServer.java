package com.mndk.tilemapper.tile.server;

import com.mndk.tilemapper.tile.TileImageData;
import com.mndk.tilemapper.tile.TilePosToUrlFunction;
import com.mndk.tilemapper.tile.TilePosition;
import com.mndk.tilemapper.tile.projection.TileServerProjection;
import com.mndk.tilemapper.util.Bounds2d;
import com.mndk.tilemapper.util.MemoryCache;
import lombok.Getter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Getter
public class TileServer {

    private final TileServerProjection projection;
    private final TilePosToUrlFunction urlFunction;
    private final ExecutorService executorService;
    private final MemoryCache<TilePosition, BufferedImage> cache;
    private final HttpClient httpClient;

    public TileServer(TileServerProjection projection, TilePosToUrlFunction urlFunction,
                      int maximumConcurrentRequests, int cacheSize) {
        this.projection = projection;
        this.urlFunction = urlFunction;
        this.executorService = Executors.newFixedThreadPool(maximumConcurrentRequests);
        this.cache = new MemoryCache<>(cacheSize);
        this.httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .build();
    }

    /**
     * Fetch a tile image asynchronously. Results are cached in MemoryCache.
     */
    public CompletableFuture<TileImageData> fetch(TilePosition pos) {
        URI uri;
        try {
            uri = URI.create(this.urlFunction.get(pos).toString());
        } catch (MalformedURLException e) {
            return CompletableFuture.failedFuture(e);
        }

        HttpRequest request = HttpRequest.newBuilder().uri(uri).build();

        // Try cache first; if miss, do async HTTP fetch + decode on executorService
        return cache.getAsync(pos, () ->
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                        .thenApplyAsync(response -> {
                            try (InputStream is = response.body()) {
                                BufferedImage image = ImageIO.read(is);
                                if (image == null) {
                                    throw new IOException("ImageIO.read returned null for " + uri);
                                }
                                return image;
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to decode tile: " + uri, e);
                            }
                        }, executorService)
        ).thenApply(image -> new TileImageData(pos, image, this));
    }

    /**
     * Fetch a tile image by geographic coordinates.
     */
    public CompletableFuture<TileImageData> fetch(double lon, double lat, int zoom) {
        TilePosition pos = this.projection.toTileCoordinates(lon, lat, zoom);
        return this.fetch(pos);
    }

    /**
     * Fetch all intersecting tiles for a given bounds asynchronously.
     */
    public CompletableFuture<TileImageData[]> fetchAllAsync(Bounds2d bounds2d, int zoom) {
        TilePosition[] posList = this.projection.getAllIntersecting(bounds2d, zoom);
        List<CompletableFuture<TileImageData>> futures = new ArrayList<>();
        for (TilePosition pos : posList) {
            futures.add(this.fetch(pos));
        }
        CompletableFuture<?>[] cfs = futures.toArray(new CompletableFuture[0]);
        return CompletableFuture.allOf(cfs).thenApply(ignored ->
                futures.stream().map(CompletableFuture::join).toArray(TileImageData[]::new));
    }
}
