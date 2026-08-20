# Reference configuration documents from module instances

Module instances reference versioned configuration documents rather than storing settings directly. Copy duplicates the document; the post-first-release clone feature will link several instances to one document while retaining independent placements, map coordinates, transient state, and widget bindings. Building this indirection into the first schema avoids migrating every saved module instance when clone arrives.
