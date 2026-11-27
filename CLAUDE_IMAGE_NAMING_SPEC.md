# Claude Image Naming Spec

These rules are taken from the existing importer (`ProjectExcelUploadService.processZipImages` / `planImage` / `resolveProjectFromZipKey`). They are applied by the connector's isolated image path, with stricter validation so that nothing is attached to the wrong slot or project silently.

## File name

```
<Project-Key>_<Type>_My-Property-Fact.<jpg|jpeg|png|webp>
```

- Split on `_`. Part 1 is the **project key**, part 2 is the **type**. Anything after part 2 is ignored (the `My-Property-Fact` suffix is convention).
- **Project key = PROJECT NAME with spaces replaced by `-`.** It is matched by URL slug: `generateSlug(key with '-' → ' ')` must equal the row's slug. It is **not** a database ID and **not** the workbook's `ID` column. `Victory-Ace` matches the row "Victory Ace". `Victory-Ace-7` would look for a project named "Victory Ace 7".
- Avoid `_` inside the project key. Punctuation is dropped by the slug, so "Children's Park" becomes `Childrens-Park`.

## Types

| Type token (case-insensitive) | Slot | Stored as | Max per project |
|---|---|---|---|
| `Logo` | `projects.project_logo` | fit within 792×203 | 1 |
| `Thumbnail` | `projects.project_thumbnail` | fit within 800×800 | 1 |
| `Location` or `LocationMap` | `projects.location_map` | fit within 1200×800 | 1 |
| `Desktop-1`, `Desktop-2`… (`DesktopBanner`) | `project_desktop_banners` | fit within 2508×1200 | any |
| `Mobile-1`, `Mobile-2`… (`MobileBanner`) | `project_mobile_banners` | fit within 800×800 | any |
| `Gallery-1`, `Gallery-2`… | `project_gallery` (type `gallery`) | fit within 1600×1200 | any |

**Rejected:** any other token, for example `ProjectLogo`, `ProjectThumbnail` or `Opening-1`. The existing importer would silently save those as gallery images. The error message suggests the right token.

Every image is **resized to fit the box (aspect kept, not cropped; small images are upscaled and sharpened) and re-encoded**, exactly as the admin importer does. Provide images at least the box size with the same aspect ratio. Otherwise the report warns `IMAGE_WILL_BE_UPSCALED` / `IMAGE_ASPECT_RATIO`.

## ZIP layout

```
properties_images.zip
├── The-Core-Ultra-Wide/
│   ├── The-Core-Ultra-Wide_Desktop-1_My-Property-Fact.jpg
│   ├── The-Core-Ultra-Wide_Mobile-1_My-Property-Fact.jpg
│   ├── The-Core-Ultra-Wide_Thumbnail_My-Property-Fact.jpg
│   ├── The-Core-Ultra-Wide_Location_My-Property-Fact.jpg
│   ├── The-Core-Ultra-Wide_Logo_My-Property-Fact.png
│   ├── The-Core-Ultra-Wide_Gallery-1_My-Property-Fact.jpg
│   └── The-Core-Ultra-Wide_Gallery-2_My-Property-Fact.jpg
└── Metro-Suites-Glitz/
    └── Metro-Suites-Glitz_Desktop-1_My-Property-Fact.jpg …
```

- Folders are optional. If used, the folder name must be the same project key (`IMAGE_FOLDER_MISMATCH` otherwise).
- **File names must be unique across the whole ZIP.** The existing importer flattens folders, so same-named files would overwrite each other.
- Only `.jpg`, `.jpeg`, `.png` and `.webp`. Content is checked by magic bytes and a full decode, including end-of-file markers, so truncated files are rejected. The extension must match the content.
- `__MACOSX/`, `.DS_Store`, `Thumbs.db` and `desktop.ini` are ignored (reported as info).
- Every image's project key must be a row in the **same workbook** (`IMAGE_PROJECT_NOT_IN_WORKBOOK` blocks the batch). Images are only ever attached to projects created by the same job.
- At least Desktop, Mobile and Thumbnail are expected per project (warning `IMAGE_CORE_MISSING`; set `CLAUDE_INTEGRATION_REQUIRECOREIMAGES=true` to make it blocking).
- Each side must be at least 100 px. Each image may be at most 60 megapixels and 40 MB.

## Where files go

`${upload_dir}properties/<slug>/<timestamp>_<type>_<8-char-uuid>.<ext>`. Alt tag: `"<Project Name> - <type with '-' as space>"`, for example `Victory Ace - Gallery 2`. Both are identical to the admin importer. If a project's image records cannot be saved, the files that call wrote are removed. No other file is ever deleted.

## Image rights (workflow rule)

Use only official images you are permitted to use, licensed images, or AI-generated illustrations that are clearly treated as illustrations. Never use images copied from property portals. See `CLAUDE_PROPERTY_WORKFLOW.md` §6.
