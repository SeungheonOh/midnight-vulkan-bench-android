//! Benchmark circuits spanning dense MSM-heavy and wide ZkStdLib Poseidon profiles.

use std::sync::Mutex;

use midnight_circuits::{
    hash::poseidon::PoseidonChip,
    instructions::{AssignmentInstructions, PublicInputInstructions, hash::HashCPU},
};
use midnight_curves::Fq;
use midnight_proofs::{
    circuit::{Layouter, Value as CircuitValue},
    plonk::Error as PlonkError,
};
use midnight_zk_stdlib::{KZG, Relation, ZkStdLib, ZkStdLibArch, cost_model};
use serde_json::{Value, json};

pub use crate::rc1_chain;

/// A chain of `rounds` Poseidon hashes of three inputs.
#[derive(Clone, Copy, Debug)]
pub struct PoseidonChain {
    pub rounds: usize,
}

impl PoseidonChain {
    pub fn instance(&self, w: &[Fq; 3]) -> Fq {
        let mut h = <PoseidonChip<Fq> as HashCPU<Fq, Fq>>::hash(w);
        for _ in 1..self.rounds {
            h = <PoseidonChip<Fq> as HashCPU<Fq, Fq>>::hash(&[h, w[1], w[2]]);
        }
        h
    }
}

impl Relation for PoseidonChain {
    type Instance = Fq;
    type Witness = [Fq; 3];
    type Error = PlonkError;

    fn format_instance(instance: &Self::Instance) -> Result<Vec<Fq>, PlonkError> {
        Ok(vec![*instance])
    }

    fn circuit(
        &self,
        std_lib: &ZkStdLib,
        layouter: &mut impl Layouter<Fq>,
        _instance: CircuitValue<Self::Instance>,
        witness: CircuitValue<Self::Witness>,
    ) -> Result<(), PlonkError> {
        let w = std_lib.assign_many(layouter, &witness.transpose_array())?;
        let mut h = std_lib.poseidon(layouter, &w)?;
        for _ in 1..self.rounds {
            h = std_lib.poseidon(layouter, &[h, w[1].clone(), w[2].clone()])?;
        }
        std_lib.constrain_as_public_input(layouter, &h)
    }

    fn used_chips(&self) -> ZkStdLibArch {
        ZkStdLibArch {
            poseidon: true,
            ..ZkStdLibArch::default()
        }
    }

    fn write_relation<W: std::io::Write>(&self, writer: &mut W) -> std::io::Result<()> {
        writer.write_all(&(self.rounds as u64).to_le_bytes())
    }

    fn read_relation<R: std::io::Read>(reader: &mut R) -> std::io::Result<Self> {
        let mut b = [0u8; 8];
        reader.read_exact(&mut b)?;
        Ok(PoseidonChain {
            rounds: u64::from_le_bytes(b) as usize,
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Circuit {
    Chain,
    PoseidonChain,
    PoseidonPreimage,
}

pub const ALL: [Circuit; 3] = [
    Circuit::Chain,
    Circuit::PoseidonChain,
    Circuit::PoseidonPreimage,
];

impl Circuit {
    pub fn id(self) -> &'static str {
        match self {
            Circuit::Chain => "chain",
            Circuit::PoseidonChain => "poseidon-chain",
            Circuit::PoseidonPreimage => "poseidon-preimage",
        }
    }

    pub fn parse(id: &str) -> Option<Circuit> {
        ALL.into_iter().find(|c| c.id() == id)
    }

    pub fn k_range(self) -> (u32, u32) {
        match self {
            Circuit::Chain => (13, 20),
            Circuit::PoseidonChain | Circuit::PoseidonPreimage => (13, 18),
        }
    }

    fn name(self) -> &'static str {
        match self {
            Circuit::Chain => "Squaring Chain",
            Circuit::PoseidonChain => "Poseidon Chain",
            Circuit::PoseidonPreimage => "Poseidon Preimage",
        }
    }

    fn summary(self) -> &'static str {
        match self {
            Circuit::Chain => {
                "Dense 4-advice-column squaring chain (x -> x^2 + 3, 2^k - 16 rows). MSM-heavy."
            }
            Circuit::PoseidonChain => {
                "Chained Poseidon hashes on Midnight ZkStdLib filling 2^k rows. Heavy MSM + quotient + IFFT."
            }
            Circuit::PoseidonPreimage => {
                "Single Poseidon hash on Midnight ZkStdLib in 2^k rows (sparse witness, quotient-heavy)."
            }
        }
    }

    pub fn describe(self) -> Value {
        let (k_min, k_max) = self.k_range();
        json!({
            "id": self.id(),
            "name": self.name(),
            "summary": self.summary(),
            "k_min": k_min,
            "k_max": k_max,
        })
    }
}

fn chain_fits(rounds: usize, k: u32) -> bool {
    cost_model::<_, KZG>(&PoseidonChain { rounds }, Some(k), false).k <= k
}

pub fn poseidon_rounds(k: u32) -> Option<usize> {
    static KNOWN: Mutex<Vec<(u32, usize)>> = Mutex::new(Vec::new());
    if let Some(&(_, r)) = KNOWN.lock().unwrap().iter().find(|(kk, _)| *kk == k) {
        return Some(r);
    }
    if !chain_fits(1, k) {
        return None;
    }
    let rows = |rounds| cost_model::<_, KZG>(&PoseidonChain { rounds }, Some(k), false).rows;
    let (one, two) = (rows(1), rows(2));
    let per = two.saturating_sub(one).max(1);
    let base = one.saturating_sub(per);
    let mut rounds = ((1usize << k).saturating_sub(base) / per).max(1);
    while rounds > 1 && !chain_fits(rounds, k) {
        rounds -= (rounds / 100).max(1);
    }
    KNOWN.lock().unwrap().push((k, rounds));
    Some(rounds)
}
