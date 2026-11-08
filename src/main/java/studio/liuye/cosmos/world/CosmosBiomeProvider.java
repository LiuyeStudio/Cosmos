package studio.liuye.cosmos.world;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

import java.util.List;

/**
 * 单一生物群系的 {@link BiomeProvider}。
 *
 * <p>自定义 {@link org.bukkit.generator.ChunkGenerator} 必须提供 biome provider，
 * 否则服务端会警告并对每个方块回退到默认群系。这里恒定返回配置的群系
 * （默认 {@code the_end}），因此每一次查询都是纯函数、线程安全。</p>
 */
public final class CosmosBiomeProvider extends BiomeProvider {

    private final Biome biome;

    public CosmosBiomeProvider(Biome biome) {
        this.biome = biome == null ? Biome.THE_END : biome;
    }

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        return biome;
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        return List.of(biome);
    }
}
