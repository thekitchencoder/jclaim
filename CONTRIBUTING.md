# Contributing to JClaim

Thank you for considering contributing to JClaim! This document provides
guidelines and information for contributors.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [How to Contribute](#how-to-contribute)
- [Coding Standards](#coding-standards)
- [Testing Guidelines](#testing-guidelines)
- [Submitting Changes](#submitting-changes)
- [Reporting Bugs](#reporting-bugs)
- [Suggesting Enhancements](#suggesting-enhancements)

## Code of Conduct

This project adheres to the [Contributor Covenant](CODE_OF_CONDUCT.md). By
participating, you are expected to uphold it.

## Getting Started

1. Fork the repository on GitHub
2. Clone your fork locally
3. Set up the development environment
4. Create a feature branch
5. Make your changes
6. Submit a pull request

## Development Setup

### Requirements

- **Java 21** or higher
- **Maven 3.6+**
- Git
- IDE with Java 21 support (IntelliJ IDEA, Eclipse, VS Code)

### Initial Setup

```bash
# Clone your fork
git clone https://github.com/YOUR_USERNAME/jclaim.git
cd jclaim

# Add upstream remote
git remote add upstream https://github.com/thekitchencoder/jclaim.git

# Install dependencies and build
mvn clean install

# Run tests to verify setup
mvn test
```

### IDE Configuration

#### IntelliJ IDEA

1. Import as Maven project
2. Enable annotation processing for Lombok:
   - Settings → Build, Execution, Deployment → Compiler → Annotation Processors
   - Check "Enable annotation processing"
3. Install Lombok plugin if not already installed
4. Set project SDK to Java 21

#### Eclipse

1. Import as Maven project
2. Install Lombok:
   - Download lombok.jar
   - Run `java -jar lombok.jar`
   - Select Eclipse installation
3. Set Java compliance to 21

#### VS Code

1. Install Java Extension Pack
2. Install Lombok Annotations Support extension
3. Ensure Java 21 is configured in settings

## How to Contribute

### Types of Contributions

We welcome various types of contributions:

- **Bug fixes** — Fix existing issues
- **New features** — Add storage adapters, matching primitives, observability
- **Documentation** — Improve docs, add examples
- **Tests** — Increase test coverage
- **Performance** — Optimise existing code
- **Examples** — Add usage examples

### Contribution Workflow

1. **Check existing issues** — See if someone else is working on it
2. **Create an issue** — Discuss significant changes before implementing
3. **Fork and branch** — Create a feature branch from `main`
4. **Implement changes** — Follow coding standards
5. **Write tests** — Add tests for new functionality
6. **Update documentation** — Update relevant docs
7. **Submit PR** — Create pull request with clear description

## Coding Standards

### Java Style Guide

Follow standard Java conventions with these specifics:

#### Formatting

- **Indentation**: 4 spaces (no tabs)
- **Line length**: 120 characters maximum
- **Braces**: K&R style (opening brace on same line)
- **Imports**: No wildcards, organise logically

#### Naming Conventions

- **Classes**: PascalCase (`EntityResolver`, `InMemoryEntityStorage`)
- **Methods**: camelCase (`resolveOrMint`, `findByAlias`)
- **Constants**: UPPER_SNAKE_CASE (`DEFAULT_NAMESPACE`)
- **Variables**: camelCase (`claim`, `entity`, `resolutionResult`)
- **Packages**: lowercase (`uk.codery.jclaim.<feature>`)

#### Code Organisation

- One class per file (except inner classes / sealed permits)
- Logical grouping — related methods together
- Private first — order: fields → constructors → public methods → private methods
- Prefer records and immutable collections

### Design Principles

#### 1. Immutability

All domain models are immutable Java records. Defensively copy collection inputs
in compact constructors.

#### 2. Thread Safety

Code must be thread-safe by default. Avoid mutable shared state. Storage
adapters must enforce alias uniqueness atomically.

#### 3. No Spring Dependency

JClaim must remain Spring-independent. Avoid annotations from `org.springframework`
in core packages. Integrations live in optional, separate modules if added later.

#### 4. Logging over Printing

Use SLF4J logger, never `System.out` / `System.err`.

### Java 21 Features

Leverage modern Java features:

- **Records** for immutable data
- **Sealed interfaces** for restricted hierarchies (e.g. `ResolutionResult`)
- **Pattern matching** in switch expressions
- **Text blocks** for multi-line strings

## Testing Guidelines

### Test Coverage Requirements

- New features: must have tests
- Bug fixes: add a regression test
- Target coverage: 80%+ line coverage per package (enforced via JaCoCo)
- Critical paths (alias uniqueness, conflict detection): aim for 100%

### Test Structure

Use AAA pattern (Arrange, Act, Assert) with AssertJ fluent assertions:

```java
@Test
void resolveOrMint_firstClaim_mintsNewEntity() {
    // Arrange
    EntityResolver resolver = new DefaultEntityResolver(...);
    Claim claim = new Claim(new SourceSystem("ecommerce"), "cust-123", List.of(
        new MatchingAttribute("email", "alice@example.com")
    ));

    // Act
    ResolutionResult result = resolver.resolveOrMint(claim);

    // Assert
    assertThat(result).isInstanceOf(ResolutionResult.Minted.class);
    assertThat(result.entity().aliases()).contains(claim.asAlias());
}
```

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=DefaultEntityResolverTest

# Run with coverage
mvn test jacoco:report
```

## Submitting Changes

### Before Submitting

- [ ] All tests pass (`mvn test`)
- [ ] Code follows style guidelines
- [ ] New tests added for new functionality
- [ ] Documentation updated (README, Javadoc, CHANGELOG)
- [ ] Commit messages are clear and descriptive
- [ ] No unnecessary dependencies added

### Pull Request Process

1. Update your fork:

   ```bash
   git fetch upstream
   git rebase upstream/main
   ```

2. Create feature branch:

   ```bash
   git checkout -b feature/your-feature-name
   ```

3. Make commits with conventional-commit prefixes:

   ```bash
   git commit -m "feat: add postgres storage adapter"
   ```

4. Push to your fork:

   ```bash
   git push origin feature/your-feature-name
   ```

5. Open a pull request on GitHub with:
   - Clear title describing the change
   - Description of what changed and why
   - Link to related issue (if applicable)

### Commit Message Format

Follow conventional commits:

```
<type>(<scope>): <subject>
```

**Types**:

- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `test`: Adding tests
- `refactor`: Code refactoring
- `perf`: Performance improvement
- `chore`: Build/tooling changes

## Reporting Bugs

Open a GitHub issue with:

- A clear description of the bug
- Steps to reproduce
- Expected vs actual behaviour
- Environment (Java version, JClaim version, OS)
- Minimal code example

## Suggesting Enhancements

Open a GitHub issue describing:

- The use case and the problem it solves
- A proposed solution
- Alternatives considered

## Questions?

- **Documentation**: Check [README.md](README.md) and [CLAUDE.md](CLAUDE.md)
- **Issues**: Open a GitHub issue for discussion

Thank you for contributing to JClaim!
