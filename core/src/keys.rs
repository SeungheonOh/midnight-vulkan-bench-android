//! Deterministic keygen, proving, and verification for every benchmark circuit.

use std::time::Instant;

use midnight_curves::{Bls12, Fq};
use midnight_gpu_prover::{
    ServedRelation,
    example::{PoseidonPreimage, example_input},
};
use midnight_proofs::{
    plonk::{self, ProvingKey, create_proof, keygen_pk, keygen_vk_with_k},
    poly::{commitment::Guard, kzg::params::ParamsKZG},
    transcript::{CircuitTranscript, Transcript},
};
use midnight_zk_stdlib::{KZG, MidnightPK, MidnightVK, Relation};
use rand::{RngCore, SeedableRng, rngs::OsRng};
use rand_chacha::ChaCha20Rng;
use serde_json::{Value, json};

use crate::circuits::{self, Circuit, PoseidonChain, rc1_chain::Chain};

type Blake = CircuitTranscript<blake2b_simd::State>;

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1e3
}

pub trait Proving: Send {
    fn pk(&self) -> &ProvingKey<Fq, KZG>;
    fn prove(&self, params: &ParamsKZG<Bls12>) -> Result<Vec<u8>, String>;
    fn verify(&self, params: &ParamsKZG<Bls12>, proof: &[u8]) -> Result<(), String>;
}

struct StdKeys<R: Relation> {
    relation: R,
    vk: MidnightVK<KZG>,
    pk: MidnightPK<R, KZG>,
    instance: Fq,
    witness: [Fq; 3],
}

impl<R> Proving for StdKeys<R>
where
    R: Relation<Instance = Fq, Witness = [Fq; 3]> + Send,
{
    fn pk(&self) -> &ProvingKey<Fq, KZG> {
        self.pk.pk()
    }

    fn prove(&self, params: &ParamsKZG<Bls12>) -> Result<Vec<u8>, String> {
        midnight_zk_stdlib::prove::<R, blake2b_simd::State, KZG>(
            params,
            &self.pk,
            &self.relation,
            &self.instance,
            self.witness,
            OsRng,
        )
        .map_err(|e| format!("{e:?}"))
    }

    fn verify(&self, params: &ParamsKZG<Bls12>, proof: &[u8]) -> Result<(), String> {
        midnight_zk_stdlib::verify::<R, blake2b_simd::State, KZG>(
            &params.verifier_params(),
            &self.vk,
            &self.instance,
            None,
            proof,
        )
        .map_err(|e| format!("{e:?}"))
    }
}

struct ChainKeys {
    chain: Chain,
    pk: ProvingKey<Fq, KZG>,
    public: [Fq; 2],
}

impl Proving for ChainKeys {
    fn pk(&self) -> &ProvingKey<Fq, KZG> {
        &self.pk
    }

    fn prove(&self, params: &ParamsKZG<Bls12>) -> Result<Vec<u8>, String> {
        let mut t = Blake::init();
        create_proof::<Fq, KZG, _, _>(
            params,
            &self.pk,
            &self.chain,
            0,
            &[&self.public],
            &mut t,
            OsRng,
        )
        .map_err(|e| format!("{e:?}"))?;
        Ok(t.finalize())
    }

    fn verify(&self, params: &ParamsKZG<Bls12>, proof: &[u8]) -> Result<(), String> {
        let vk = self.pk.get_vk();
        let mut t = Blake::init_from_bytes(proof);
        plonk::prepare::<Fq, KZG, _>(vk, &[], &[&self.public], &mut t)
            .map_err(|e| format!("{e:?}"))?
            .verify(&params.verifier_params())
            .map_err(|e| format!("{e:?}"))
    }
}

pub fn keygen(
    circuit: Circuit,
    k: u32,
    params: &ParamsKZG<Bls12>,
) -> Result<(Box<dyn Proving>, Value), String> {
    match circuit {
        Circuit::Chain => {
            let chain = Chain { k };
            let t = Instant::now();
            let vk =
                keygen_vk_with_k::<_, KZG, _>(params, &chain, k).map_err(|e| format!("{e:?}"))?;
            let vk_ms = ms(t);
            let t = Instant::now();
            let pk = keygen_pk(vk, &chain).map_err(|e| format!("{e:?}"))?;
            let pk_ms = ms(t);
            let public = chain.instance();
            Ok((
                Box::new(ChainKeys { chain, pk, public }),
                json!({ "vk_ms": vk_ms, "pk_ms": pk_ms, "rows_used": (1u64 << k) - 16 }),
            ))
        }
        Circuit::PoseidonChain => {
            let rounds = circuits::poseidon_rounds(k)
                .ok_or_else(|| format!("no Poseidon chain fits in 2^{k} rows"))?;
            let relation = PoseidonChain { rounds };
            let mut rng = ChaCha20Rng::seed_from_u64(0x504f_5343_4841_494eu64 ^ u64::from(k));
            let w = [
                Fq::from(rng.next_u64()),
                Fq::from(rng.next_u64()),
                Fq::from(rng.next_u64()),
            ];
            let instance = relation.instance(&w);
            let (keys, mut v) = std_keygen(relation, params, instance, w)?;
            v["rounds"] = json!(rounds);
            Ok((keys, v))
        }
        Circuit::PoseidonPreimage => {
            let relation = PoseidonPreimage;
            let (instance, witness) = relation.decode_input(&example_input(0x5052_4549_4d41_4745u64 ^ u64::from(k)))?;
            std_keygen(relation, params, instance, witness)
        }
    }
}

fn std_keygen<R>(
    relation: R,
    params: &ParamsKZG<Bls12>,
    instance: Fq,
    witness: [Fq; 3],
) -> Result<(Box<dyn Proving>, Value), String>
where
    R: Relation<Instance = Fq, Witness = [Fq; 3]> + Send + 'static,
{
    let t = Instant::now();
    let vk = midnight_zk_stdlib::setup_vk::<R, KZG>(params, &relation);
    let vk_ms = ms(t);
    let t = Instant::now();
    let pk = midnight_zk_stdlib::setup_pk(&relation, &vk);
    let pk_ms = ms(t);
    Ok((
        Box::new(StdKeys {
            relation,
            vk,
            pk,
            instance,
            witness,
        }),
        json!({ "vk_ms": vk_ms, "pk_ms": pk_ms }),
    ))
}
