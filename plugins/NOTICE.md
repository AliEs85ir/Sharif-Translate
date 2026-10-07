# Sharif Translate bundled plugins

Current development and maintenance: Ali Esmaeili (AliEs85ir), 2026.

The Google, Bing and AI plugin modules are maintained as part of Sharif Translate.
Their displayed author identifies the current developer and maintainer. This
release rewrites Google spell-check orchestration, Bing authentication management
and the shared AI text/vision request pipeline, with regression tests.

These modules are derived from the original application by Ahmed Hatem. Retained
source and interfaces remain subject to their original MIT copyright notice;
the root LICENSE accompanies each bundled plugin JAR. Attribution does not
configure a repository, updater, account, donation link or remote dependency.
This release is not presented as an independently authored replacement of every
line of the original modules.

Google and Microsoft endpoints belong to the respective service providers.
AI requests use the endpoint, model and credentials supplied by the user.
No developer-owned AI API key or intermediary billing service is bundled.
