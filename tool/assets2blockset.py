#!/usr/bin/env python3
"""
============================================================
assets2blockset — All-in-one Minecraft block texture processing tool
============================================================
Integrates the core pipeline from [jar2blockset](https://github.com/1280px/hueblocks/tree/master/jar2blockset) but sources data from
[Minecraft Assets](https://github.com/InventivetalentDev/minecraft-assets), eliminating the need for JAR files.

NEW: Builds a texture-to-Block-ID reverse index from _all.json,
     accurately mapping each texture to its real Block ID.

Designed to run in Google Colab:
    !pip install numpy Pillow requests
    !python assets2blockset.py

Requirements: numpy, Pillow, requests
============================================================
"""

import os
import sys
import re
import math
import json
import time
import zipfile
import argparse
from datetime import datetime, timezone
from io import BytesIO
from shutil import rmtree

import numpy as np
import requests
from PIL import Image


# ============================================================
# Embedded default configuration
# ============================================================

DEFAULT_CONFIG = {
    'version': '1.21.11',
    'exclude-alpha': 'semi',
    'exclude-animated': False,
    'colorcalc': 'modern',
    'naming': {
        'textures': 'title',
        'blockset': 'title',
    },
    # Output filter: keep only specified faces (e.g. ['bottom', 'sides']).
    # Computation still uses DEFAULT_FACING; set to None or 'default' to output all six faces
    'facing': ['top'],
    # Output palette files: specify which palette files to generate (e.g. ['All', 'Default']).
    # Each file filters blocks according to its palette condition.
    # Set to None or 'default' to output all palettes
    'palette': None,
}

DEFAULT_FACING = {
    'top': [
        '$_top.png',
        '$_front_vertical.png',
        '$_top_on.png',
        '$_top_off.png',
        'farmland$',
        '$path_top.png',
        'podzol.png',
        'crimson_nylium.png',
        'warped_nylium.png',
        'pumpkin_top.png',
        'melon_top.png',
        'beehive_end.png',
        'netherite_top.png',
        'chiseled_bookshelf_top.png',
        'crafter_top$',
    ],
    'bottom': [
        '$_bottom.png',
        '$_front_vertical.png',
        'daylightDetector_side.png',
        'daylight_detector_side.png',
        'beehive_end.png',
        'melon_top.png',
        'netherite_top.png',
        'pumpkin_top.png',
        'chiseled_bookshelf_top.png',
        'crafter_bottom$',
    ],
    'north': [
        'workbench_front.png',
        'crafting_table_front.png',
        'fletching_table_front.png',
        'smithing_table_front.png',
        'cartography_table_side3.png',
    ],
    'south': [
        'workbench_front.png',
        'crafting_table_front.png',
        'fletching_table_front.png',
        'smithing_table_front.png',
        'cartography_table_side1.png',
    ],
    'east': [
        'workbench_side.png',
        'crafting_table_side.png',
        'fletching_table_side.png',
        'smithing_table_side.png',
        'cartography_table_side3.png',
    ],
    'west': [
        'workbench_side.png',
        'crafting_table_side.png',
        'fletching_table_side.png',
        'smithing_table_side.png',
        'cartography_table_side2.png',
    ],
    'sides': [
        '$_side.png',
        '$_front.png',
        '$_front_lit.png',
        '$_front_on.png',
        '$_front_off.png',
        '$_front_horizontal.png',
        'crafter_$',
        'grass_block_snow.png',
        'grass_side_snowed.png',
        'bookshelf.png',
        'chiseled_bookshelf_$',
        'pumpkin_$',
        'carved_pumpkin.png',
        'jack_o_lantern.png',
        '$sandstone_normal.png',
        '$sandstone_smooth.png',
        '$sandstone_carved.png',
        'chiseled_sandstone.png',
        'chiseled_red_sandstone.png',
        'cut_sandstone.png',
        'cut_red_sandstone.png',
        'chiseled_tuff.png',
    ],
    'all': [
        'furnace_top.png',
        'piston$',
        'quartzblock_lines$',
        'quartz_block_lines$',
        'quartz_pillar$',
        'bone_block$',
        'purpur_pillar$',
        'basalt$',
        'polished_basalt$',
        'hay_block$',
        'barrel_$',
        'creaking_heart$',
        'tree_top.png',
        'log_acacia_top.png',
        'log_big_oak_top.png',
        'log_birch_top.png',
        'log_jungle_top.png',
        'log_oak_top.png',
        'log_spruce_top.png',
        '$_log_top.png',
        '$_stem_top.png',
        'sandstone_top.png',
        'red_sandstone_top.png',
        'smooth_stone.png',
        'stone_slab_top.png',
    ],
}

DEFAULT_BLACKLIST = [
    # 1.5.1
    'anvil_base.png', 'beacon.png', 'brewingStand_base.png',
    'bed_$', 'cauldron_$', 'comparator$',
    'doorIron_lower.png', 'doorWood_lower.png', 'destroy_$',
    'dragonEgg.png', 'dropper_front.png', 'enchantment_$',
    'endframe_top.png', 'grass_$', 'hopper$', 'itemframe$',
    'lava$', 'leaves_$', 'mycel_side.png', 'piston_bottom.png',
    'piston_inner_top.png', 'portal.png', 'quartz_block_chiseled.png',
    'quartz_block_chiseled_top.png', 'quartz_block_lines_top.png',
    'repeater$', 'snow_side.png', 'stonebricksmooth_cracked.png',
    'water$', 'workbench_front.png',
    # 1.12.2
    'brewing_stand_base.png', 'chorus_$', 'chain_command_block_$',
    'command_block_$', 'crafting_table_front.png',
    'dispenser_front_vertical.png', 'dirt_podzol_side.png',
    'dragon_egg.png', 'dropper_front_horizontal.png',
    'dropper_front_vertical.png', 'enchanting_table_$',
    'end_portal_frame_top.png', 'frosted_ice_0.png',
    'frosted_ice_1.png', 'frosted_ice_2.png',
    'furnace_front_on.png', 'jukebox$', 'mycelium_side.png',
    'nether_portal.png', 'observer_front.png', 'observer_back$',
    'piston_inner.png', 'quartzblock_chiseled$',
    'quartz_block_chiseled$', 'quartzblock_lines_top.png',
    'repeating_command_block_$', 'stonebrick_cracked.png',
    'structure_block$', 'debug$',
    # 1.14.4
    'acacia_log_top.png', 'anvil.png', 'bamboo_stalk.png',
    'barrel_bottom.png', 'barrel_top_open.png', 'birch_door$',
    'birch_log_top.png', 'blast_furnace_front$', 'campfire$',
    'cartography_table_side1.png', 'cartography_table_side3.png',
    'dark_oak_door$', 'dark_oak_log_top.png',
    'fletching_table_$', 'grass_block_$', 'item_frame.png',
    'iron_door$', 'jigsaw$', 'jungle_log_top.png', 'jungle_door$',
    'lectern_$', 'oak_door$', 'oak_log_top.png',
    'podzol_side.png', 'smithing_table_side.png', 'smoker_front$',
    'spruce_door$', 'spruce_log_top.png', 'stonecutter_top.png',
    # 1.20.1
    'bamboo_door$', 'bamboo_fence$', 'bamboo_mosaic.png',
    'bee_nest_front$', 'beehive_front$',
    'calibrated_sculk_sensor_top.png', 'cherry_log_top.png',
    'chiseled_quartz_block$', 'cracked_deepslate_bricks.png',
    'cracked_deepslate_tiles.png', 'cracked_nether_bricks.png',
    'cracked_polished_blackstone_bricks.png',
    'cracked_stone_bricks.png', 'crimson_door$',
    'crimson_stem_top.png', 'crimson_nylium_side.png',
    'glow_item_frame.png', 'lightning_rod$', 'mangrove_door$',
    'mangrove_log_top.png', 'pink_petals_stem.png',
    'quartz_bricks.png', 'quartz_pillar_top.png',
    'respawn_anchor_bottom.png', 'respawn_anchor_side$',
    'sculk_catalyst_side_bloom.png', 'sculk_catalyst_top$',
    'sculk_sensor_$', 'sculk_shrieker_$',
    'soul_campfire_log_lit.png', 'suspicious_gravel$',
    'suspicious_sand$', 'warped_door$', 'warped_stem_top.png',
    'warped_nylium_side.png',
    # 1.21.5
    '$copper_bulb_lit_powered.png', '$copper_bulb_powered.png',
    '$copper_door_top.png', '$copper_door_bottom.png',
    'chiseled_tuff_bricks.png', 'crafter_east$', 'crafter_north_$',
    'crafter_south_$', 'crafter_west$', 'crafter_top_triggered.png',
    'pale_moss_carpet.png', 'pale_oak_door$', 'pale_oak_log_top.png',
    'test_$', 'trial_spawner_top$', 'vault_bottom$', 'vault_top$',
    # 26.2
    'dried_ghast_$', '$_shelf.png',
    '$bed_head_up.png', '$bed_foot_up.png',
]

DEFAULT_PALETTES = [
    {
        'name': 'Default',
        'excludes': [
            '$_froglight_top.png', '$_glazed_terracotta.png', '$_ore.png',
            '$_log_top.png', '$shulker_box.png', '$_stained_glass.png',
            '$_trapdoor.png', '$copper_bulb_lit.png', '$copper_bulb.png',
            'barrel_$', 'basalt_side.png', 'bee_nest_$', 'beehive_side.png',
            'bookshelf.png', 'bone_block_$', 'cartography_table$',
            'carved_pumpkin.png', 'chiseled_bookshelf_occupied.png',
            'chiseled_tuff$', 'command_block.png', 'commandBlock.png',
            'composter_bottom.png', 'crafter_$', 'crafting_table_$',
            'creaking_$', 'daylight_detector$', 'daylightDetector$',
            'deepslate.png', 'dispenser_front$', 'farmland.png',
            'farmland$', 'flowering$', 'frosted_ice$', 'furnace_$',
            'glass$', 'glowstone.png', 'honey_block$', 'ice.png',
            'jack_o_lantern.png', 'lodestone$', 'loom$', 'magma.png',
            'mushroom_block_inside.png', 'musicBlock.png', 'note_block.png',
            'noteblock.png', 'observer$', 'polished_basalt_$',
            'powder_snow.png', 'piston$', 'pumpkin_face$',
            'pumpkin_jack.png', 'redstone_lamp$', 'redstoneLight$',
            'reinforced_deepslate$', 'respawn_anchor$', 'sculk_catalyst$',
            'sea_lantern.png', 'shroomlight.png', 'slime$',
            'smithing_table_$', 'smoker$', 'stonecutter$', 'target$',
            'tinted_glass.png', 'tnt$', 'warped_stem.png', 'workbench$',
        ],
    },
    {
        'name': 'Grayscale blocks',
        'includes': [
            '$_black.png', '$_gray.png', '$_silver.png',
            '$_white.png', '$andesite.png', '$basalt_side.png',
            '$basalt_top.png', '$diorite.png', '$snow.png',
            'acacia_log.png', 'bedrock.png', 'birch_log.png',
            'black_$', 'blackstone$', 'blast_furnace_$',
            'blockIron.png', 'bone_block$', 'calcite.png',
            'chiseled_deepslate.png', 'chiseled_polished_blackstone.png',
            'chiseled_stone_bricks.png', 'clay.png',
            'cloth_0.png', 'cloth_7.png', 'cloth_8.png', 'cloth_15.png',
            'coal$', 'cobbled_deepslate.png', 'cobblestone.png',
            'crafter_bottom.png', 'creaking_heart_top.png',
            'cyan_terracotta.png', 'dead_$', 'deepslate_bricks.png',
            'deepslate_coal$', 'deepslate_tiles.png', 'deepslate_top.png',
            'deepslate.png', 'gravel.png',
            'gray_$', 'hardened_clay_stained_cyan.png', 'iron_block.png',
            'light_gray_$', 'log_acacia.png',
            'log_birch.png', 'mud.png',
            'mushroom_block_skin_stem.png', 'mushroom_skin_stem.png',
            'mushroom_stem.png', 'netherite_block.png',
            'obsidian.png', 'oreCoal.png', 'pale_moss_block.png',
            'pale_oak_log.png', 'pale_oak_planks.png',
            'pale_oak_trapdoor.png', 'piston_bottom.png',
            'polished_blackstone$', 'polished_deepslate.png',
            'polished_tuff.png', 'quartzblock_$', 'quartz_$',
            'reinforced_deepslate$', 'smithing_table_top.png',
            'smooth_basalt.png',
            'smooth_stone$', 'stone_andesite$', 'stone_bricks.png',
            'stone_diorite$', 'stone_slab$', 'stone.png',
            'stonebrick$', 'stonecutter_bottom.png', 'stoneslab_$',
            'stripped_pale_oak_$', 'tree_birch.png',
            'tuff$', 'white_$',
        ],
        'excludes': [
            '$_ore.png', '$_shulker_box.png', '$_stained_glass.png',
            'black_glazed_terracotta.png', 'black_terracotta.png',
            'chiseled_tuff$', 'dispenser_$', 'furnace$',
            'gray_terracotta.png', 'hardened_clay_stained_black.png',
            'hardened_clay_stained_gray.png',
            'hardened_clay_stained_silver.png',
            'hardened_clay_stained_white.png',
            'lodestone$', 'light_gray_glazed_terracotta.png',
            'light_gray_terracotta.png', 'observer$',
            'smoker$', 'stonebrick_mossy.png', 'stonebricksmooth_mossy.png',
            'tinted_glass.png', 'white_glazed_terracotta.png', 'white_terracotta.png',
        ],
    }
]

# ============================================================
# Palette Output Map (output filename → palette name mapping)
# ============================================================

PALETTE_OUTPUT_MAP = {
    'All': None,  # Full set, no palette filtering applied
    'Default': 'Default',
    'Grayscale': 'Grayscale blocks',
}


# ============================================================
# Block Replacement Rules (handle shared texture naming)
# Maps texture names to correct Block IDs when blocks share textures.
# Ported from BLOCK_REPLACEMENT_RULES in bloxData.py.
# ============================================================

BLOCK_REPLACEMENT_RULES = {
    r'^(.*)_log$': r'\1_wood',
    r'^(.*)crimson_stem$': r'\1crimson_hyphae',
    r'^(.*)warped_stem$': r'\1warped_hyphae',
    r'^(.*)quartz_block_bottom$': r'\1smooth_quartz',
}


# ============================================================
# ASSETS GITHUB URLS
# ============================================================

ASSET_DOWNLOAD_URL = (
    'https://github.com/InventivetalentDev/minecraft-assets/archive/refs/tags/{version}.zip'
)
MODELS_LIST_URL = (
    'https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/refs/heads/'
    '{version}/assets/minecraft/models/block/_all.json'
)

# ============================================================
# REMOTE JSON FETCHER
# ============================================================

def fetch_json_resource(url, description='resource'):
    """Fetch and parse JSON from a remote URL with error handling."""
    try:
        response = requests.get(url, timeout=30)
        response.raise_for_status()
        return response.json()
    except requests.exceptions.RequestException as e:
        print(f'  [Error] Failed to fetch {description}: {e}')
        return None


# ============================================================
# Texture → Block ID Reverse Index
# (ported from the model-mapping logic in bloxData.py)
# ============================================================

def extract_strings_from_structure(val):
    """
    Recursively extract all string values from nested dicts/lists.
    e.g. {"top": "minecraft:block/oak_log_top", "side": ["oak_log", "planks"]}
    -> yields "minecraft:block/oak_log_top", "oak_log", "planks"
    """
    if isinstance(val, str):
        yield val
    elif isinstance(val, dict):
        for v in val.values():
            yield from extract_strings_from_structure(v)
    elif isinstance(val, list):
        for v in val:
            yield from extract_strings_from_structure(v)


def build_texture_to_blockid_map(all_textures, models_manifest):
    """
    Build a reverse index mapping texture names to Block IDs that reference them.

    all_textures: set of texture names (str, without .png extension)
    models_manifest: content of _all.json (dict)

    Returns: { texture_name: [block_id, ...], ... }
    """
    texture_to_ids = {tex: [] for tex in all_textures}

    for model_id, model_content in models_manifest.items():
        textures_dict = model_content.get('textures', {})
        if not textures_dict:
            continue

        for tex_val in textures_dict.values():
            for tex_path in extract_strings_from_structure(tex_val):
                # Extract "minecraft:block/oak_log_top" → "oak_log_top"
                if '/' in tex_path:
                    tex_name = tex_path.split('/')[-1]
                else:
                    tex_name = tex_path.replace('minecraft:', '')

                # Only record textures present in all_textures
                if tex_name in texture_to_ids:
                    if model_id not in texture_to_ids[tex_name]:
                        texture_to_ids[tex_name].append(model_id)

    return texture_to_ids


def resolve_texture_blockid(texture_name, texture_to_ids):
    """
    Resolve a texture name to its most likely Block ID.

    Priority rules (consistent with bloxData.py):
    1. If the texture name exactly matches a Block ID -> use it directly
    2. Otherwise strip trailing suffixes after the last _ (e.g. stone_bricks_mossy -> stone_bricks)
    3. If still not found, return None
    """
    if texture_name in texture_to_ids and texture_to_ids[texture_name]:
        # If texture name is itself a Block ID, prioritise it
        if texture_name in texture_to_ids[texture_name]:
            return texture_name
        # Otherwise take the first associated Block ID (most direct match)
        return texture_to_ids[texture_name][0]

    # If texture name is not in the index, try recursively stripping suffixes
    # e.g. "crafter_top_triggered" -> "crafter_top" -> "crafter"
    parts = texture_name.split('_')
    for i in range(len(parts) - 1, 0, -1):
        candidate = '_'.join(parts[:i])
        if candidate in texture_to_ids and texture_to_ids[candidate]:
            if candidate in texture_to_ids[candidate]:
                return candidate
            return texture_to_ids[candidate][0]

    return None


# ============================================================
# Filters (ported from jar2blockset/filters.py)
# ============================================================

def compute_filter(lines, names, title=''):
    """Compute filter rules with $ wildcard support (prefix/suffix matching)."""
    if lines is None:
        print(f'  Warning — Filter "{title}" has no lines specified!')
        return {}

    if title:
        print(f'  Filter "{title}":')

    rules = {}
    if title:
        print(f'    - Computing filter from {len(lines)} lines...')

    for line in lines:
        if line == '$':
            print('    Wildcard filter "$" is not allowed — skipping.')
            continue

        line_star = line.find('$')

        if line_star == -1:          # No wildcard — exact match
            rules[line] = 'R'
        elif line_star == len(line) - 1:  # Prefix wildcard (prefix$)
            prefix = line.split('$')[0]
            for name in names:
                if name.startswith(prefix):
                    if name not in rules or rules[name] != 'R':
                        rules[name] = 'W'
        elif line_star == 0:              # Suffix wildcard ($suffix)
            suffix = line.split('$')[1]
            for name in names:
                if name.endswith(suffix):
                    if name not in rules or rules[name] != 'R':
                        rules[name] = 'W'

    if title:
        print(f'    - Finished computation into {len(rules)} rules.\n')
    return rules


# ============================================================
# Color Calculation (ported from jar2blockset/colorcalc.py)
# ============================================================

def add_srgb_gamma(c_lrgb):
    """Convert linear RGB to sRGB (apply gamma)."""
    if c_lrgb >= 0.0031308:
        return 1.055 * (c_lrgb ** (1.0 / 2.4)) - 0.055
    else:
        return c_lrgb * 12.92


def remove_srgb_gamma(c_srgb):
    """Convert sRGB to linear RGB (remove gamma)."""
    if c_srgb >= 0.04045:
        return ((c_srgb + 0.055) / 1.055) ** 2.4
    else:
        return c_srgb / 12.92


def img2rgb(texture_path, colorcalc_rule='modern'):
    """
    Compute the average RGB color of a texture.
    modern: remove sRGB gamma -> mean of squares -> reapply gamma (more vibrant colors)
    legacy: resize to 1x1 pixel (compatible with legacy HueBlocks)
    """
    img = Image.open(texture_path)

    if colorcalc_rule == 'modern':
        srgb2lrgb = np.vectorize(lambda color: remove_srgb_gamma(color / 255.0))
        lrgb2srgb = np.vectorize(lambda color: add_srgb_gamma(color) * 255.0)

        img_np = np.asarray(img.convert('RGB'), dtype=np.float64)
        mean_of_squares = np.mean(srgb2lrgb(img_np), axis=(0, 1))
        sqrts_of_mean = np.clip(lrgb2srgb(mean_of_squares), 0, 255)

        img.close()
        return [int(c) for c in sqrts_of_mean]

    elif colorcalc_rule == 'legacy':
        img_proc = img.resize((1, 1), Image.Resampling.LANCZOS)
        img_proc_np = np.asarray(img_proc.convert('RGB'))
        color = img_proc_np[0][0]
        img.close()
        return [int(c) for c in color]

    else:
        print(f'  Error: Unknown colorcalc rule "{colorcalc_rule}"!')
        img.close()
        sys.exit(1)


def img2lab(texture_path, srgb=None):
    """
    Convert texture RGB to Oklab color space.
    Uses the same matrix transformation as the vueblocks frontend colors.ts.
    Outputs 20 decimal places to avoid frontend parsing issues.
    """
    if srgb is None:
        srgb = img2rgb(texture_path, 'modern')

    # sRGB -> linear RGB
    lrgb = np.array([remove_srgb_gamma(c / 255.0) for c in srgb])

    # Linear RGB -> CIEXYZ -> LMS
    l = 0.4122214708 * lrgb[0] + 0.5363325363 * lrgb[1] + 0.0514459929 * lrgb[2]
    m = 0.2119034982 * lrgb[0] + 0.6806995451 * lrgb[1] + 0.1073969566 * lrgb[2]
    s = 0.0883024619 * lrgb[0] + 0.2817188376 * lrgb[1] + 0.6299787005 * lrgb[2]

    # LMS -> LMS'
    l_ = l ** (1 / 3)
    m_ = m ** (1 / 3)
    s_ = s ** (1 / 3)

    # LMS' -> Oklab
    lab = np.array([
        +0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
        +1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
        +0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_,
    ])

    return [f'{lab[0]:.20f}', f'{lab[1]:.20f}', f'{lab[2]:.20f}']


# ============================================================
# Block Data (ported from jar2blockset/blockdata.py)
# ============================================================

def get_blacklisted_blocks(blacklist_rules, textures_names):
    """
    Compute the list of textures to exclude using blacklist rules.
    blacklist_rules: list of filter rule strings (from YAML filters field)
    textures_names: list of all texture file names
    """
    blacklist = compute_filter(blacklist_rules, textures_names, 'blacklist')
    return list(blacklist.keys())


def get_facing_rules(facing_dict, textures_names):
    """
    Compute the face(s) for each texture based on facing rules.
    facing_dict: dict with keys top/bottom/north/south/east/west/sides/all,
                 values are filter rule lists
    textures_names: list of all texture file names (.png only)
    """
    sides = ['top', 'bottom', 'north', 'south', 'east', 'west', 'sides', 'all']

    facing_filters = {}
    for side in sides:
        rules = facing_dict.get(side, [])
        facing_filters[side] = compute_filter(rules, textures_names, f'facing/{side}')

    # Merge multiple facing filter results into per-texture face lists
    textures_to_sides = {}

    for name in textures_names:
        name_present = {side: facing_filters[side].get(name, 'X') for side in sides}

        present_sides = []
        if name_present['all'] != 'X':
            present_sides = sides.copy()[:-2]  # top, bottom, north, south, east, west
        else:
            for side in sides[:-2]:
                if name_present[side] != 'X':
                    present_sides.append(side)

            if not present_sides:
                if name_present['sides'] != 'X':
                    present_sides = sides.copy()[2:-2]  # north, south, east, west
                else:
                    present_sides = sides.copy()[:-2]  # Default: all six faces

        textures_to_sides[name] = present_sides

    return textures_to_sides


def generate_naming(string, naming_rule):
    """Generate a texture/blockset name according to naming rules."""
    if naming_rule == 'none':
        return string

    string_new = os.path.splitext(string)[0]
    string_new = ' '.join(string_new.split('_'))
    string_new = ' '.join(string_new.split('-'))

    if naming_rule == 'title':
        return string_new.title()
    elif naming_rule == 'capital':
        return string_new.capitalize()
    elif naming_rule == 'sep':
        return string_new
    else:
        print(f'  Error: Unknown naming rule "{naming_rule}"!')
        sys.exit(1)


def generate_textures_data(textures_path, facing_filters, naming_rule, colorcalc_rule,
                          blockid_map=None):
    """
    Generate structured data for each texture (name, RGB, Lab, facing, Block ID).
    Returns a list conforming to _blockdata.json format.

    blockid_map: {texture_name -> Block ID} mapping from build_texture_to_blockid_map()
    """
    textures_data = []

    textures_names = sorted(os.listdir(textures_path))
    textures_names = list(filter(lambda f: f.endswith('.png'), textures_names))

    for texture_name in textures_names:
        texture_sides = facing_filters.get(
            texture_name,
            ['top', 'bottom', 'north', 'south', 'east', 'west']
        )

        texture_path = os.path.join(textures_path, texture_name)
        texture_rgb = img2rgb(texture_path, colorcalc_rule)
        texture_lab = img2lab(texture_path, texture_rgb)

        # Resolve Block ID (from _all.json reverse index)
        tex_name_noext = os.path.splitext(texture_name)[0]

        # Apply Block Replacement Rules (handle shared texture naming)
        replaced_name = tex_name_noext
        for pattern, replacement in BLOCK_REPLACEMENT_RULES.items():
            if re.match(pattern, tex_name_noext):
                replaced_name = re.sub(pattern, replacement, tex_name_noext)
                break

        block_id = resolve_texture_blockid(tex_name_noext, blockid_map) if blockid_map else None
        # If replacement was applied, use the replaced name as id
        final_id = replaced_name if replaced_name != tex_name_noext else block_id

        texture_data = {
            'name': generate_naming(replaced_name + '.png', naming_rule),
            'texture': texture_name,
            'id': final_id,
            'rgb': texture_rgb,
            'lab': texture_lab,
            'sides': texture_sides,
        }
        textures_data.append(texture_data)

    return textures_data


# ============================================================
# Palettes (ported from jar2blockset/palettes.py)
# ============================================================

def generate_palette(palette_raw, textures_names, title='palette'):
    """
    Compute a single palette based on includes/excludes rules.
    Returns a sorted list of texture names that match this palette.
    """
    includes_rules = palette_raw.get('includes')
    excludes_rules = palette_raw.get('excludes')

    if includes_rules:
        palette_in = compute_filter(includes_rules, textures_names, f'{title} includes')
    else:
        palette_in = {name: 'X' for name in textures_names}

    if excludes_rules:
        palette_ex = compute_filter(excludes_rules, textures_names, f'{title} excludes')
    else:
        palette_ex = {}

    palette = [rule for rule in palette_in.keys() if rule not in palette_ex.keys()]
    palette = [rule for rule in palette if rule in textures_names]

    return sorted(palette)


def get_palettes_data(palettes_list, textures_names):
    """
    Compute all palette data from a list of palette definitions.
    palettes_list: [{'name': ..., 'includes': [...], 'excludes': [...]}, ...]
    textures_names: list of all texture file names (.png only)
    """
    palettes_data = []

    for palette_raw in palettes_list:
        palette_data = {}
        palette_data['name'] = palette_raw['name']

        palette_res = generate_palette(palette_raw, textures_names, palette_data['name'])
        palette_data['textures'] = palette_res
        palette_data['count'] = len(palette_res)

        palettes_data.append(palette_data)

    return palettes_data


# ============================================================
# Assets Downloader
# ============================================================

def download_assets(version, extract_dir):
    """
    Download and extract Minecraft Assets for the specified version from GitHub.
    Returns the path to the textures directory.
    """
    zip_url = ASSET_DOWNLOAD_URL.format(version=version)
    zip_name = f'minecraft-assets-{version}.zip'
    textures_rel = f'minecraft-assets-{version}/assets/minecraft/textures/block'

    # Return early if already downloaded and extracted
    textures_dir = os.path.join(extract_dir, textures_rel.replace('/', os.sep))
    if os.path.isdir(textures_dir):
        print(f'  [Assets] Textures directory already exists: {textures_dir}')
        return textures_dir

    # Download ZIP
    print(f'  [Assets] Downloading {zip_url} ...')
    os.makedirs(extract_dir, exist_ok=True)
    zip_path = os.path.join(extract_dir, zip_name)

    if not os.path.isfile(zip_path):
        response = requests.get(zip_url, stream=True, timeout=60)
        response.raise_for_status()

        total_size = int(response.headers.get('content-length', 0))
        downloaded = 0
        chunk_size = 8192

        with open(zip_path, 'wb') as f:
            for chunk in response.iter_content(chunk_size=chunk_size):
                if chunk:
                    f.write(chunk)
                    downloaded += len(chunk)
                    if total_size > 0:
                        pct = downloaded / total_size * 100
                        print(f'\r    Progress: {pct:.1f}% ({downloaded // 1024**2}MB / {total_size // 1024**2}MB)', end='')
        print()
    else:
        print(f'  [Assets] ZIP already exists: {zip_path}')

    # Extract
    print(f'  [Assets] Extracting {zip_name} ...')
    with zipfile.ZipFile(zip_path, 'r') as zf:
        zf.extractall(extract_dir)

    if not os.path.isdir(textures_dir):
        print(f'  [Error] Textures directory not found after extraction: {textures_dir}')
        sys.exit(1)

    print(f'  [Assets] Extracted to {textures_dir}')
    return textures_dir


# ============================================================
# Main Pipeline
# ============================================================

def main():
    parser = argparse.ArgumentParser(
        description='Process Minecraft block textures using GitHub Assets',
    )
    parser.add_argument(
        '--output', '-o', default=None,
        help='Output directory (default: ./output/<version>)',
    )
    parser.add_argument(
        '--colorcalc', default=None,
        choices=['modern', 'legacy'],
        help='Color calculation method (default: modern)',
    )
    parser.add_argument(
        '--naming', default=None,
        choices=['title', 'capital', 'sep', 'none'],
        help='Texture naming rule (default: title)',
    )
    parser.add_argument(
        '--exclude-alpha', default=None,
        choices=['full', 'semi', 'none'],
        help='Alpha transparency exclusion mode (default: semi)',
    )
    parser.add_argument(
        '--exclude-animated', action='store_true',
        help='Exclude animated textures (default: blend first+last frame)',
    )
    parser.add_argument(
        '--keep-temp', action='store_true',
        help='Keep temporary texture directory (for debugging)',
    )

    args, _ = parser.parse_known_args()

    # ----------------------------------------------------------
    # Merge configuration (priority: CLI args > DEFAULT_CONFIG > original constants)
    # ----------------------------------------------------------
    config = dict(DEFAULT_CONFIG)

    if args.colorcalc:
        config['colorcalc'] = args.colorcalc
    if args.naming:
        config['naming']['textures'] = args.naming
        config['naming']['blockset'] = args.naming
    if args.exclude_alpha:
        config['exclude-alpha'] = args.exclude_alpha
    if args.exclude_animated:
        config['exclude-animated'] = True

    version = config['version']
    print(f'\n{"="*60}')
    print(f'  assets2blockset v1.1 — with Block ID resolution')
    print(f'  Minecraft Assets Version: {version}')
    print(f'{"="*60}\n')

    # ----------------------------------------------------------
    # Step 1: Download and extract Assets
    # ----------------------------------------------------------
    print('[Step 1/6] Downloading Minecraft Assets...')
    work_dir = os.path.abspath(args.output or os.path.join('output', version))
    extract_dir = os.path.join(work_dir, '.cache')
    textures_dir = download_assets(version, extract_dir)

    # Copy textures to working directory
    textures_work = os.path.join(work_dir, 'textures')
    if os.path.isdir(textures_work):
        rmtree(textures_work)
    os.makedirs(textures_work, exist_ok=True)

    for fname in os.listdir(textures_dir):
        if fname.endswith('.png') or fname.endswith('.mcmeta'):
            src = os.path.join(textures_dir, fname)
            dst = os.path.join(textures_work, fname)
            with open(src, 'rb') as s, open(dst, 'wb') as d:
                d.write(s.read())

    total_extracted = len([f for f in os.listdir(textures_work) if f.endswith('.png')])
    print(f'  [Step 1] Extracted {total_extracted} textures to {textures_work}\n')

    # ----------------------------------------------------------
    # Step 2: Filter textures
    # ----------------------------------------------------------
    print('[Step 2/6] Filtering textures...')

    textures_names = sorted(os.listdir(textures_work))
    removed_count = 0

    for fname in textures_names:
        fpath = os.path.join(textures_work, fname)
        if not fname.endswith('.png'):
            continue

        img = Image.open(fpath)
        img_np = np.asarray(img)

        # (A) Alpha filtering
        if config['exclude-alpha'] != 'none':
            img_rgba = img.convert('RGBA')
            img_rgba_np = np.asarray(img_rgba)
            alpha_threshold = 255 if config['exclude-alpha'] == 'full' else 1

            if img_rgba_np[:, :, 3].min() < alpha_threshold:
                os.remove(fpath)
                removed_count += 1
                img.close()
                continue

        # (B) Animated texture handling
        fname_noext = os.path.splitext(fname)[0]
        has_mcmeta = os.path.isfile(os.path.join(textures_work, fname_noext + '.mcmeta'))
        has_txt = os.path.isfile(os.path.join(textures_work, fname_noext + '.txt'))

        if has_mcmeta or has_txt:
            if config['exclude-animated']:
                os.remove(fpath)
                removed_count += 1
                img.close()
                continue
            else:
                # Blend first frame + last frame
                frame_h = img_np.shape[1]  # Animated texture height = width (square frame)
                img_first = img.convert('RGBA').crop((0, 0, frame_h, frame_h))
                img_last = img.convert('RGBA').crop(
                    (0, img_np.shape[0] - frame_h, frame_h, img_np.shape[0])
                )
                blended = Image.blend(img_first, img_last, alpha=0.5)
                blended.convert('RGB').save(fpath)

        img.close()

    # (C) Blacklist filtering
    textures_names = sorted(os.listdir(textures_work))
    textures_png = [f for f in textures_names if f.endswith('.png')]
    blacklisted = get_blacklisted_blocks(DEFAULT_BLACKLIST, textures_png)

    for fname in blacklisted:
        fpath = os.path.join(textures_work, fname)
        if os.path.isfile(fpath):
            os.remove(fpath)
            removed_count += 1

    # Clean up leftover .mcmeta / .txt files
    for fname in sorted(os.listdir(textures_work)):
        if fname.endswith('.mcmeta') or fname.endswith('.txt'):
            os.remove(os.path.join(textures_work, fname))

    remaining = len([f for f in os.listdir(textures_work) if f.endswith('.png')])
    print(f'  [Step 2] Removed {removed_count} textures, {remaining} remaining\n')

    # ----------------------------------------------------------
    # Step 3: Compute facing rules
    # ----------------------------------------------------------
    print('[Step 3/6] Computing facing rules...')
    textures_png = sorted([f for f in os.listdir(textures_work) if f.endswith('.png')])
    facing_rules = get_facing_rules(DEFAULT_FACING, textures_png)

    # Output filter: if config['facing'] specifies a face whitelist, keep only those faces
    # Supports 'sides' (-> north/south/east/west) and 'all' (-> all six faces) as shortcuts
    facing_filter = config.get('facing')
    if facing_filter and facing_filter != 'default':
        # Expand shortcut keywords
        expanded_filter = set()
        for item in facing_filter:
            if item == 'sides':
                expanded_filter.update(['north', 'south', 'east', 'west'])
            elif item == 'all':
                expanded_filter.update(['top', 'bottom', 'north', 'south', 'east', 'west'])
            else:
                expanded_filter.add(item)
        # Filter and remove textures with no matching faces
        tex_to_remove = []
        for tex_name, sides in facing_rules.items():
            kept = [s for s in sides if s in expanded_filter]
            if not kept:
                tex_to_remove.append(tex_name)
            # Keep all original faces for textures that have at least one matching face
        for tex_name in tex_to_remove:
            del facing_rules[tex_name]
            # Also delete the texture file so it doesn't appear in final output
            tex_path = os.path.join(textures_work, tex_name)
            if os.path.isfile(tex_path):
                os.remove(tex_path)
        # Update remaining count and textures_png (for subsequent steps)
        remaining -= len(tex_to_remove)
        textures_png = sorted([f for f in os.listdir(textures_work) if f.endswith('.png')])
        print(f'  [Step 3] Filtered to sides: {facing_filter} → {sorted(expanded_filter)}')
        print(f'  [Step 3] Removed {len(tex_to_remove)} textures with no matching sides')
    print(f'  [Step 3] Computed facing for {len(facing_rules)} textures\n')

    # ----------------------------------------------------------
    # Step 3.5: Build texture->Block ID reverse index from _all.json
    # ----------------------------------------------------------
    print('[Step 3.5/7] Resolving Block IDs from models manifest...')
    models_url = MODELS_LIST_URL.format(version=version)
    models_manifest = fetch_json_resource(models_url, 'models manifest (_all.json)')

    blockid_map = None
    if models_manifest:
        # Build set of texture names (without extension)
        texture_names_noext = set(
            os.path.splitext(f)[0] for f in textures_png
        )
        texture_to_ids = build_texture_to_blockid_map(texture_names_noext, models_manifest)
        blockid_map = texture_to_ids

        # Print statistics
        resolved_count = 0
        for tex_name in texture_names_noext:
            if resolve_texture_blockid(tex_name, blockid_map):
                resolved_count += 1
        print(f'  [Step 3.5] Resolved Block IDs for {resolved_count}/{len(texture_names_noext)} textures')
    else:
        print('  [Step 3.5] Warning: Could not fetch _all.json, blockId will be null')
    print()

    # ----------------------------------------------------------
    # Step 4: Generate palette-based output files
    # ----------------------------------------------------------
    print('[Step 4/6] Generating palette-based blockdata files...')

    # Build complete blockdata set
    blockdata_all = generate_textures_data(
        textures_work,
        facing_rules,
        config['naming']['textures'],
        config['colorcalc'],
        blockid_map=blockid_map,
    )

    # Determine which palette files to output
    palette_output_keys = config.get('palette')
    if not palette_output_keys or palette_output_keys == 'default':
        palette_output_keys = list(PALETTE_OUTPUT_MAP.keys())

    output_files = []
    for output_key in palette_output_keys:
        if output_key == 'All':
            # All.json: no palette filtering, output everything
            filtered_data = [
                entry for entry in blockdata_all if isinstance(entry, dict)
            ]
            count_label = 'all textures'
        else:
            palette_name = PALETTE_OUTPUT_MAP.get(output_key)
            if palette_name is None:
                print(f'  [Step 4] Warning: Unknown output key "{output_key}", skipping')
                continue
            palette_raw = next(
                (p for p in DEFAULT_PALETTES if p['name'] == palette_name),
                None,
            )
            if palette_raw is None:
                print(f'  [Step 4] Warning: Palette "{palette_name}" not found, skipping')
                continue
            palette_textures = generate_palette(
                palette_raw, textures_png,
                f'{output_key}',
            )
            filtered_data = [
                entry for entry in blockdata_all
                if isinstance(entry, dict) and entry.get('texture') in palette_textures
            ]
            count_label = f'{len(filtered_data)} blocks'

        file_data = filtered_data
        out_filename = f'{output_key}.json'
        out_path = os.path.join(work_dir, out_filename)
        with open(out_path, 'w', encoding='utf-8') as f:
            json.dump(file_data, f, indent=2, ensure_ascii=True)
        output_files.append(out_filename)
        print(f'  [Step 4] Saved {out_filename} ({count_label})')
    print()

    # ----------------------------------------------------------
    # Step 5: Update blockset metadata
    # ----------------------------------------------------------
    print('[Step 5/6] Updating blockset metadata...')
    blocksets_path = os.path.join(work_dir, '..', '_blocksets.json')
    blocksets_path = os.path.normpath(blocksets_path)

    descriptions = []
    if os.path.isfile(blocksets_path):
        with open(blocksets_path, 'r', encoding='utf-8') as f:
            descriptions = json.load(f)
        print(f'  [Step 6] Found existing _blocksets.json ({len(descriptions)} entries)')
        descriptions = [d for d in descriptions if d.get('dir') != work_dir]

    generated_at = str(datetime.now(timezone.utc)).split('.')[0] + ' UTC'
    all_count = len([e for e in blockdata_all if isinstance(e, dict)])
    description_res = {
        'name': generate_naming(f'minecraft_{version}', config['naming']['blockset']),
        'dir': work_dir,
        'count': all_count,
        'generatedAt': generated_at,
    }
    descriptions.append(description_res)

    with open(blocksets_path, 'w', encoding='utf-8') as f:
        json.dump(descriptions, f, indent=2, ensure_ascii=True)
    print(f'  [Step 5] Updated {blocksets_path}\n')

    # ----------------------------------------------------------
    # Step 6: Print summary
    # ----------------------------------------------------------
    # Count blockId resolution rate (based on All.json)
    if blockid_map:
        resolved = sum(
            1 for entry in blockdata_all
            if isinstance(entry, dict) and entry.get('blockId')
        )
        total_data = sum(
            1 for entry in blockdata_all if isinstance(entry, dict)
        )
        print(f'  [Step 6/6] Block ID resolution: {resolved}/{total_data} ({resolved*100//total_data if total_data else 0}%)\n')
    else:
        print('[Step 6/6] Skipped (no model data)\n')

    # ----------------------------------------------------------
    # Clean up temporary files
    # ----------------------------------------------------------
    if not args.keep_temp:
        if os.path.isdir(extract_dir):
            rmtree(extract_dir)
        if os.path.isdir(textures_work):
            rmtree(textures_work)
        print('  [Cleanup] Removed temporary directories\n')

    # ----------------------------------------------------------
    # Done
    # ----------------------------------------------------------
    print(f'{"="*60}')
    print(f'  Done! Output directory: {work_dir}')
    print(f'  Files generated:')
    for fname in output_files:
        print(f'    - {os.path.join(work_dir, fname)}')
    print(f'    - {blocksets_path}')
    print(f'  Total blocks (All): {all_count}')
    print(f'{"="*60}\n')


if __name__ == '__main__':
    main()