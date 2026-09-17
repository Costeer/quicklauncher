# Review smell baseline

The repository's documented standards override this list. Treat every item here as a judgement call and skip rules that tooling enforces.

- **Mysterious Name.** A name does not reveal what a value or operation means. Rename it. If no honest name fits, revisit the design.
- **Duplicated Code.** The same logic shape appears in several changed locations. Extract one shared implementation.
- **Feature Envy.** A method reaches into another object's data more than its own. Move the method toward the data it uses.
- **Data Clumps.** The same fields or parameters travel together. Give the group a type.
- **Primitive Obsession.** A primitive represents a domain concept with its own invariants. Give the concept a type.
- **Repeated Switches.** The same branch over a type recurs. Centralize the mapping or use polymorphism.
- **Shotgun Surgery.** One logical change requires scattered edits. Move the changing behavior behind one module.
- **Divergent Change.** One file changes for unrelated reasons. Split the responsibilities.
- **Speculative Generality.** An abstraction or hook has no current requirement. Inline or remove it.
- **Message Chains.** A caller navigates through several objects. Hide the traversal behind the first owner.
- **Middle Man.** A type mostly delegates. Call the owned behavior directly.
- **Refused Bequest.** An implementation ignores most inherited behavior. Prefer composition.

